package com.tavi.novelreader.parser

import org.jsoup.Jsoup
import java.io.File
import java.io.RandomAccessFile

class DrmException(msg: String) : Exception(msg)

/**
 * 纯 Kotlin 的 MOBI / AZW3（KF8）解析：读取 PalmDB 记录，解压 PalmDOC（LZ77）文本，
 * 取出 HTML 与图片后交给现有的富文本管线。受 DRM 保护的文件直接拒绝。
 * 暂不支持 HUFF/CDIC 压缩（少数大型 Kindle 文件使用）。
 */
class MobiBook(file: File) {
    var title: String = file.nameWithoutExtension; private set
    var author: String? = null; private set
    var coverIndex: Int = -1; private set
    val isKf8: Boolean
    private val records: List<ByteArray>
    private var firstImage = -1
    private val hdr: Header
    private val base: Int

    init {
        val raw = RandomAccessFile(file, "r").use { f -> ByteArray(f.length().toInt()).also { f.readFully(it) } }
        require(raw.size > 78) { "文件太小，不是有效的 MOBI" }
        val type = String(raw, 60, 8, Charsets.ISO_8859_1)
        require(type == "BOOKMOBI" || type == "TEXtREAd") { "不是 MOBI / AZW3 文件" }
        val n = u16(raw, 76)
        val offs = IntArray(n) { u32(raw, 78 + it * 8).toInt() }
        records = (0 until n).map { i -> raw.copyOfRange(offs[i], if (i + 1 < n) offs[i + 1] else raw.size) }
        var r0 = records[0]
        var b0 = 0
        val hdr = parseHeader(r0)
        // 组合文件（MOBI6 + KF8）：EXTH 121 指向 KF8 段，优先读取 KF8 段
        val boundary = hdr.exth[121]?.let { u32(it, 0).toInt() }?.takeIf { it in 1 until n && it != 0xFFFFFFFF.toInt() }
        isKf8 = hdr.version >= 8 || boundary != null
        val use = if (boundary != null) { b0 = boundary; r0 = records[boundary]; parseHeader(r0) } else hdr
        hdr.exth[503]?.let { title = String(it, Charsets.UTF_8).trim() } ?: hdr.fullName?.let { title = it }
        hdr.exth[100]?.let { author = String(it, Charsets.UTF_8).trim() }
        firstImage = hdr.firstImage
        hdr.exth[201]?.let { coverIndex = u32(it, 0).toInt() }
        this.hdr = use
        this.base = b0
    }

    private class Header(
        val compression: Int, val textRecords: Int, val encryption: Int, val encoding: Int, val version: Int,
        val firstImage: Int, val extraFlags: Int, val fdst: Int, val exth: Map<Int, ByteArray>, val fullName: String?,
    )
    private fun parseHeader(r0: ByteArray): Header {
        val compression = u16(r0, 0)
        val textRecords = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw DrmException("此文件受 DRM 保护，无法打开")
        if (r0.size < 24 || String(r0, 16, 4, Charsets.ISO_8859_1) != "MOBI")
            return Header(compression, textRecords, 0, 1252, 1, -1, 0, -1, emptyMap(), null)
        val hl = u32(r0, 20).toInt()
        val encoding = u32(r0, 28).toInt()
        val version = u32(r0, 36).toInt()
        val firstImage = if (r0.size > 112) u32(r0, 108).toInt() else -1
        val extra = if (hl >= 0xE4 && r0.size >= 244) u16(r0, 242) else 0
        val fdst = if (version >= 8 && r0.size >= 196) u32(r0, 192).toInt() else -1
        val exth = HashMap<Int, ByteArray>()
        if (r0.size > 132 && (u32(r0, 128).toInt() and 0x40) != 0) {
            var p = 16 + hl
            if (p + 12 <= r0.size && String(r0, p, 4, Charsets.ISO_8859_1) == "EXTH") {
                val count = u32(r0, p + 8).toInt(); p += 12
                repeat(count) {
                    if (p + 8 > r0.size) return@repeat
                    val t = u32(r0, p).toInt(); val l = u32(r0, p + 4).toInt()
                    if (l < 8 || p + l > r0.size) return@repeat
                    exth.putIfAbsent(t, r0.copyOfRange(p + 8, p + l)); p += l
                }
            }
        }
        val fullName = runCatching {
            val o = u32(r0, 84).toInt(); val l = u32(r0, 88).toInt()
            if (o > 0 && l > 0 && o + l <= r0.size) String(r0, o, l, if (encoding == 65001) Charsets.UTF_8 else charset("windows-1252")) else null
        }.getOrNull()
        return Header(compression, textRecords, encryption, encoding, version, firstImage, extra, fdst, exth, fullName)
    }

    /** 解压后的全部文本（KF8 只取第一个 flow，即 HTML） */
    fun html(): String {
        val h = hdr
        if (h.compression == 17480) error("暂不支持 HUFF/CDIC 压缩的 MOBI 文件")
        require(h.compression == 1 || h.compression == 2) { "未知的压缩方式 ${h.compression}" }
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..h.textRecords) {
            val rec = records.getOrNull(base + i) ?: break
            val size = rec.size - trailingSize(rec, h.extraFlags)
            val data = rec.copyOf(size.coerceAtLeast(0))
            out.write(if (h.compression == 2) palmDoc(data) else data)
        }
        var bytes = out.toByteArray()
        if (h.fdst > 0) records.getOrNull(base + h.fdst)?.takeIf { it.size >= 12 && String(it, 0, 4, Charsets.ISO_8859_1) == "FDST" }?.let { f ->
            val s = u32(f, 12).toInt(); val e = u32(f, 16).toInt()
            if (e in (s + 1)..bytes.size) bytes = bytes.copyOfRange(s, e)
        }
        return String(bytes, if (h.encoding == 65001) Charsets.UTF_8 else charset("windows-1252"))
    }

    /** 第 n 个资源（从 0 开始）对应的图片数据 */
    fun resource(n: Int): ByteArray? {
        if (firstImage < 0) return null
        val rec = records.getOrNull(firstImage + n) ?: return null
        return rec.takeIf { isImage(it) }
    }

    fun coverBytes(): ByteArray? = coverIndex.takeIf { it >= 0 }?.let(::resource)

    /** 拆分为章节：KF8 按文件（每个 html 文档），MOBI6 按 mbp:pagebreak；过长的章节再按块切分 */
    fun chapters(imagePath: (Int) -> String?, imageDims: (String) -> Pair<Int, Int>?): List<Chapter> {
        val html = html()
        val parts = (if (isKf8) html.split(Regex("(?=<\\?xml)|(?=<html[\\s>])", RegexOption.IGNORE_CASE))
            else html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE))).filter { it.isNotBlank() }
        val out = mutableListOf<Chapter>()
        var pending = mutableListOf<Block>()
        fun emit(blocks: List<Block>) {
            if (blocks.isEmpty()) return
            var cur = mutableListOf<Block>(); var len = 0
            fun flush() {
                if (cur.isEmpty()) return
                val t = cur.firstOrNull { it.kind == BlockKind.HEADING }?.text?.trim()?.take(40)
                    ?: cur.firstOrNull { it.text.isNotBlank() }?.text?.trim()?.let { if (it.length > 20) it.take(16) + "…" else it } ?: "第 ${out.size + 1} 节"
                val imgs = cur.mapNotNull { it.image }.associateWith { imageDims(it) ?: (600 to 800) }
                out += Chapter(t, cur, imgs); cur = mutableListOf(); len = 0
            }
            for (b in blocks) {
                if (len > 30_000 && b.kind == BlockKind.HEADING) flush()
                cur += b; len += b.text.length
                if (len > 60_000) flush()
            }
            flush()
        }
        for (p in parts) {
            val doc = Jsoup.parse(p)
            // 图片引用：MOBI6 用 recindex，KF8 用 kindle:embed:XXXX（base32，从 1 开始）
            doc.select("img").forEach { img ->
                val idx = img.attr("recindex").toIntOrNull()?.minus(1)
                    ?: Regex("kindle:embed:([0-9A-V]+)").find(img.attr("src"))?.groupValues?.get(1)?.toInt(32)?.minus(1)
                val path = idx?.let(imagePath)
                if (path != null) img.attr("src", path) else img.remove()
            }
            val css = doc.select("style").joinToString("\n") { it.data() }
            val blocks = RichConverter(Css(css)) { src -> src.takeIf { it.startsWith("cache:") } }.convert(doc)
            pending.addAll(blocks)
            // 太短的片段（如单独的标题页）并入下一章
            if (pending.sumOf { it.text.length } >= 200 || pending.any { it.kind == BlockKind.IMAGE && pending.size > 1 }) { emit(pending); pending = mutableListOf() }
        }
        emit(pending)
        return out.ifEmpty { listOf(textChapter(title, listOf("（空）"))) }
    }

    companion object {
        fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, o: Int): Long = ((u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong())

        fun isImage(b: ByteArray): Boolean = b.size > 4 && (
            (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) || (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) ||
                (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) || (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()))

        fun palmDoc(src: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(src.size * 2)
            val buf = ArrayList<Byte>(4096)
            var i = 0
            fun put(b: Int) { out.write(b); buf.add(b.toByte()) }
            while (i < src.size) {
                val c = src[i++].toInt() and 0xFF
                when {
                    c == 0 || c in 0x09..0x7F -> put(c)
                    c in 1..8 -> repeat(c) { if (i < src.size) put(src[i++].toInt() and 0xFF) }
                    c in 0x80..0xBF -> {
                        if (i >= src.size) break
                        val c2 = src[i++].toInt() and 0xFF
                        val v = (c shl 8) or c2
                        val dist = (v shr 3) and 0x7FF
                        val len = (v and 7) + 3
                        val start = buf.size - dist
                        if (dist == 0 || start < 0) continue
                        repeat(len) { k -> put(buf[start + k].toInt() and 0xFF) }
                    }
                    else -> { put(' '.code); put(c xor 0x80) }
                }
            }
            return out.toByteArray()
        }

        fun trailingSize(data: ByteArray, flags: Int): Int {
            var num = 0
            var f = flags shr 1
            while (f != 0) {
                if (f and 1 != 0) {
                    var bitpos = 0; var result = 0; var p = data.size - num
                    while (p > 0) {
                        val v = data[p - 1].toInt() and 0xFF
                        result = result or ((v and 0x7F) shl bitpos)
                        bitpos += 7; p--
                        if (v and 0x80 != 0 || bitpos >= 28) break
                    }
                    num += result
                }
                f = f shr 1
            }
            if (flags and 1 != 0 && data.size - num - 1 >= 0) num += (data[data.size - num - 1].toInt() and 3) + 1
            return num.coerceIn(0, data.size)
        }
    }
}

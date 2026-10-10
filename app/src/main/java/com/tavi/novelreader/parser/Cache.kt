package com.tavi.novelreader.parser

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

/**
 * 书籍解析缓存：导入时解析一次，把每章写成独立的 JSON 文件，并保存章节索引与字数。
 * 打开书籍时只读取索引与当前章节，不再重新解析整本书。
 */
object BookCache {
    const val VERSION = 3

    fun dirFor(root: File, filePath: String) = File(root, "bookcache/" + File(filePath).nameWithoutExtension)

    class Writer(private val dir: File) {
        private val titles = mutableListOf<String>()
        private val lens = mutableListOf<Int>()
        private val paths = mutableListOf<String>()

        init { dir.deleteRecursively(); dir.mkdirs() }

        fun add(ch: Chapter) {
            File(dir, "c${titles.size}.json").writeText(ch.toJson().toString())
            titles += ch.title
            paths += ch.path ?: ""
            lens += ch.plainText().length
        }

        val count get() = titles.size

        fun finish(): List<Int> {
            if (titles.isEmpty()) add(textChapter("第 1 部分", emptyList()))
            val idx = JSONObject().put("version", VERSION).put("titles", JSONArray(titles)).put("lens", JSONArray(lens)).put("paths", JSONArray(paths))
            File(dir, "index.tmp").writeText(idx.toString())
            File(dir, "index.tmp").renameTo(File(dir, "index.json"))
            return lens.toList()
        }
    }

    fun isValid(dir: File): Boolean = runCatching {
        JSONObject(File(dir, "index.json").readText()).getInt("version") == VERSION
    }.getOrDefault(false)
}

/** 从缓存读取的书源；图片仍从原 EPUB 读取 */
class CachedSource(private val dir: File, private val imageSource: BookSource? = null) : BookSource {
    private val index = JSONObject(File(dir, "index.json").readText())
    override val chapterTitles: List<String> = index.getJSONArray("titles").let { a -> (0 until a.length()).map { a.getString(it) } }
    val lens: List<Int> = index.getJSONArray("lens").let { a -> (0 until a.length()).map { a.getInt(it) } }
    private val paths: Map<String, Int> = index.optJSONArray("paths")?.let { a -> (0 until a.length()).associateBy { a.getString(it) } } ?: emptyMap()
    override fun loadChapter(index: Int): Chapter = Chapter.fromJson(JSONObject(File(dir, "c$index.json").readText()))
    override fun image(path: String): ByteArray? =
        if (path.startsWith("cache:")) File(dir, path.removePrefix("cache:")).takeIf { it.exists() }?.readBytes() else imageSource?.image(path)
    override fun chapterOf(path: String): Int? = paths[path]
}

/** 统计已读字节数，用于导入进度 */
class CountingInputStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) { count++; onCount(count) } }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { count += it; onCount(count) } }
}

/** 流式导入 TXT：识别编码 → 逐行读取 → 按标题或字数切章 → 写入缓存 */
object TxtImporter {
    private val headingRegex = Regex(
        "^\\s*(第[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章回节卷集部篇][^\\n]{0,40}|(?:序章|序言|楔子|引子|尾声|后记|番外)[^\\n]{0,30}|Chapter\\s+\\d+[^\\n]{0,40})\\s*$",
        RegexOption.IGNORE_CASE
    )
    private const val CHUNK = 6000          // 无标题时每块字数
    private const val MAX_CHAPTER = 40000    // 单章过长时拆分

    /** 根据 BOM 或前 1MB 样本识别编码 */
    fun detectCharset(file: File): Charset {
        val head = ByteArray(1 shl 20)
        val n = file.inputStream().use { input ->
            var total = 0
            while (total < head.size) { val r = input.read(head, total, head.size - total); if (r < 0) break; total += r }
            total
        }
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        val dec: CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = java.nio.CharBuffer.allocate(n + 16)
        // 样本末尾可能截断多字节字符：endOfInput = false 时截断不算错误
        val r: CoderResult = dec.decode(ByteBuffer.wrap(head, 0, n), out, file.length() <= head.size)
        return if (r.isError) Charset.forName("GB18030") else Charsets.UTF_8
    }

    /**
     * @param onProgress 0..1
     * @return 每章字数
     */
    fun import(file: File, out: BookCache.Writer, onProgress: (Float) -> Unit = {}): List<Int> {
        val cs = detectCharset(file)
        val total = file.length().coerceAtLeast(1)
        var lastReport = 0L
        val counting = CountingInputStream(file.inputStream().buffered(1 shl 16)) { c ->
            if (c - lastReport > (1 shl 20)) { lastReport = c; onProgress(c.toFloat() / total) }
        }
        var title = ""
        var buf = ArrayList<String>()
        var len = 0
        var part = 0
        fun flush(nextTitle: String) {
            if (buf.isNotEmpty() || title.isNotEmpty()) {
                out.add(textChapter(title.ifEmpty { "第 ${++part} 部分" }, buf))
            }
            buf = ArrayList(); len = 0
            title = nextTitle
        }
        BufferedReader(InputStreamReader(counting, cs), 1 shl 16).use { r ->
            var first = true
            while (true) {
                var line = r.readLine() ?: break
                if (first) { line = line.removePrefix("\uFEFF"); first = false }
                val l = line.trim().trim('\u3000')
                if (l.isEmpty()) continue
                if (l.length <= 50 && headingRegex.matches(l)) {
                    flush(l)
                } else {
                    buf += l; len += l.length
                    if (title.isEmpty() && len >= CHUNK) flush("")
                    else if (len >= MAX_CHAPTER) {
                        val t = title
                        flush("")
                        title = if (t.endsWith("（续）")) t else "$t（续）"
                    }
                }
            }
        }
        flush("")
        onProgress(1f)
        return out.finish()
    }
}

package com.tavi.novelreader.parser

import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

class Chapter(
    val title: String,
    val paragraphs: List<String>,
    /** 图片路径 -> (宽, 高) */
    val images: Map<String, Pair<Int, Int>> = emptyMap(),
)

const val IMG_PREFIX = "\uE000IMG:"
const val OBJ = '\uFFFC'

/** 章节纯文本（与阅读页排版字符一一对应），用于进度、搜索、朗读 */
fun Chapter.plainText(): String = buildString {
    append(title); append('\n')
    paragraphs.forEachIndexed { i, p ->
        if (p.startsWith(IMG_PREFIX)) append(OBJ) else { append("\u3000\u3000"); append(p) }
        if (i != paragraphs.lastIndex) append('\n')
    }
    if (paragraphs.isEmpty()) append("\u3000\u3000（本章无文字内容）")
}

class BookMeta(val title: String?, val author: String?, val coverBytes: ByteArray?, val chapterCount: Int)

interface BookSource {
    val chapterTitles: List<String>
    fun loadChapter(index: Int): Chapter
    fun image(path: String): ByteArray? = null
}

// ---------------- TXT ----------------
object TxtParser {
    private val headingRegex = Regex(
        "^\\s*(第[0-9零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[章回节卷集部篇][^\\n]{0,40}|(?:序章|序言|楔子|引子|尾声|后记|番外)[^\\n]{0,30}|Chapter\\s+\\d+[^\\n]{0,40})\\s*$",
        RegexOption.IGNORE_CASE
    )
    private const val CHUNK = 6000

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // GB18030 是 GBK / GB2312 的超集
            String(bytes, Charset.forName("GB18030"))
        }
    }

    fun parse(text: String, fallbackTitle: String): List<Chapter> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val chapters = mutableListOf<Chapter>()
        var title = ""
        var buf = mutableListOf<String>()
        var found = false
        fun flush() {
            if (buf.isNotEmpty() || title.isNotEmpty()) {
                if (!(title.isEmpty() && buf.all { it.isBlank() }))
                    chapters += Chapter(title.ifEmpty { "前言" }, buf.toList())
            }
            buf = mutableListOf()
        }
        for (raw in lines) {
            val l = raw.trim().trim('\u3000')
            if (l.length <= 50 && headingRegex.matches(l)) {
                found = true; flush(); title = l
            } else if (l.isNotEmpty()) buf += l
        }
        flush()
        if (found && chapters.size > 1) return chapters
        // 没有识别到章节：按固定字数分块
        val paras = lines.map { it.trim().trim('\u3000') }.filter { it.isNotEmpty() }
        val out = mutableListOf<Chapter>()
        var cur = mutableListOf<String>(); var len = 0
        for (p in paras) {
            cur += p; len += p.length
            if (len >= CHUNK) { out += Chapter("第 ${out.size + 1} 部分", cur); cur = mutableListOf(); len = 0 }
        }
        if (cur.isNotEmpty() || out.isEmpty()) out += Chapter("第 ${out.size + 1} 部分", cur)
        return out
    }

    fun source(file: File, title: String): BookSource {
        val chs = parse(decode(file.readBytes()), title)
        return object : BookSource {
            override val chapterTitles = chs.map { it.title }
            override fun loadChapter(index: Int) = chs[index]
        }
    }
}

// ---------------- EPUB ----------------
class EpubBook(private val file: File) : BookSource {
    private class Item(val id: String, val href: String, val type: String, val props: String)

    var title: String? = null; private set
    var author: String? = null; private set
    var coverHref: String? = null; private set
    private val spine = mutableListOf<String>() // 完整路径
    private val tocTitles = HashMap<String, String>()
    override val chapterTitles: List<String>

    init {
        ZipFile(file).use { zip ->
            val container = zip.getInputStream(zip.getEntry("META-INF/container.xml")).use { it.readBytes() }
            val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(String(container))?.groupValues?.get(1)
                ?: error("未找到 OPF")
            val base = opfPath.substringBeforeLast('/', "")
            val items = HashMap<String, Item>()
            val spineIds = mutableListOf<String>()
            var tocId: String? = null
            var coverId: String? = null
            zip.getInputStream(zip.getEntry(opfPath)).use { input ->
                val p = newParser(input)
                var inTitle = false; var inCreator = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name.substringAfter(':')) {
                            "title" -> inTitle = title == null
                            "creator" -> inCreator = author == null
                            "meta" -> if (p.attr("name") == "cover") coverId = p.attr("content")
                            "item" -> {
                                val it = Item(p.attr("id") ?: "", p.attr("href") ?: "", p.attr("media-type") ?: "", p.attr("properties") ?: "")
                                items[it.id] = it
                            }
                            "spine" -> tocId = p.attr("toc")
                            "itemref" -> p.attr("idref")?.let { spineIds += it }
                        }
                        XmlPullParser.TEXT -> {
                            if (inTitle) { title = p.text.trim().ifEmpty { null }; inTitle = false }
                            if (inCreator) { author = p.text.trim().ifEmpty { null }; inCreator = false }
                        }
                        XmlPullParser.END_TAG -> { inTitle = false; inCreator = false }
                    }
                }
            }
            fun full(href: String) = resolve(base, href)
            val coverItem = items.values.firstOrNull { "cover-image" in it.props } ?: coverId?.let { items[it] }
                ?: items.values.firstOrNull { it.type.startsWith("image") && it.id.contains("cover", true) }
            coverHref = coverItem?.let { full(it.href) }
            spineIds.mapNotNull { items[it] }.forEach { spine += full(it.href) }

            // 目录：EPUB3 nav 优先，否则 NCX
            val nav = items.values.firstOrNull { "nav" in it.props.split(' ') }
            if (nav != null) runCatching {
                val navPath = full(nav.href)
                val doc = Jsoup.parse(zip.readText(navPath))
                val tocNav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                tocNav?.select("a[href]")?.forEach { a ->
                    val key = resolve(navPath.substringBeforeLast('/', ""), a.attr("href").substringBefore('#'))
                    tocTitles.putIfAbsent(key, a.text().trim())
                }
            }
            if (tocTitles.isEmpty()) {
                val ncx = tocId?.let { items[it] } ?: items.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
                if (ncx != null) runCatching {
                    val ncxPath = full(ncx.href)
                    val doc = Jsoup.parse(zip.readText(ncxPath), "", org.jsoup.parser.Parser.xmlParser())
                    doc.select("navPoint").forEach { np ->
                        val label = np.selectFirst("navLabel > text")?.text()?.trim() ?: return@forEach
                        val src = np.selectFirst("content")?.attr("src") ?: return@forEach
                        tocTitles.putIfAbsent(resolve(ncxPath.substringBeforeLast('/', ""), src.substringBefore('#')), label)
                    }
                }
            }
        }
        var last = ""
        chapterTitles = spine.mapIndexed { i, path ->
            val t = tocTitles[path]
            if (t != null) { last = t; t } else if (i == 0) "封面 / 前言" else if (last.isNotEmpty()) "$last（续）" else "第 ${i + 1} 节"
        }
    }

    fun coverBytes(): ByteArray? = coverHref?.let { h ->
        runCatching { ZipFile(file).use { z -> z.getEntry(h)?.let { e -> z.getInputStream(e).use { it.readBytes() } } } }.getOrNull()
    }

    override fun loadChapter(index: Int): Chapter {
        val html = ZipFile(file).use { it.readText(spine[index]) }
        val doc = Jsoup.parse(html)
        val paras = mutableListOf<String>()
        val sb = StringBuilder()
        val blocks = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "blockquote", "section", "tr", "pre", "hr")
        fun flush() { val s = sb.toString().replace(Regex("\\s+"), " ").trim(); if (s.isNotEmpty()) paras += s; sb.clear() }
        fun walk(n: Node) {
            when (n) {
                is TextNode -> sb.append(n.text())
                is Element -> {
                    val tag = n.normalName()
                    if (tag == "script" || tag == "style") return
                    if (tag == "img" || tag == "image") {
                        flush()
                        val src = n.attr("src").ifEmpty { n.attr("xlink:href") }.ifEmpty { n.attr("href") }
                        if (src.isNotEmpty() && !src.startsWith("data:")) {
                            val path = resolve(spine[index].substringBeforeLast('/', ""), src.substringBefore('#'))
                            paras += IMG_PREFIX + path
                        }
                        return
                    }
                    val block = tag in blocks
                    if (block) flush()
                    n.childNodes().forEach { walk(it) }
                    if (block) flush()
                }
            }
        }
        doc.body()?.let { walk(it) }
        flush()
        // 若首段与章节标题相同则去掉，避免重复
        val t = chapterTitles[index]
        if (paras.isNotEmpty() && paras[0] == t) paras.removeAt(0)
        val dims = HashMap<String, Pair<Int, Int>>()
        val imgs = paras.filter { it.startsWith(IMG_PREFIX) }.map { it.removePrefix(IMG_PREFIX) }.distinct()
        if (imgs.isNotEmpty()) ZipFile(file).use { z ->
            for (path in imgs) {
                val e = z.getEntry(path) ?: continue
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                z.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                if (o.outWidth > 0 && o.outHeight > 0) dims[path] = o.outWidth to o.outHeight
            }
        }
        // 无法解码的图片直接去掉
        paras.removeAll { it.startsWith(IMG_PREFIX) && it.removePrefix(IMG_PREFIX) !in dims }
        return Chapter(t, paras, dims)
    }

    override fun image(path: String): ByteArray? = runCatching {
        ZipFile(file).use { z -> z.getEntry(path)?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()

    companion object {
        private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        private fun XmlPullParser.attr(n: String): String? = getAttributeValue(null, n)
        private fun ZipFile.readText(path: String): String =
            getInputStream(getEntry(path) ?: error("缺少 $path")).use { String(it.readBytes(), Charsets.UTF_8) }

        fun resolve(base: String, href: String): String {
            val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            val parts = ArrayDeque<String>()
            if (base.isNotEmpty() && !decoded.startsWith("/")) base.split('/').forEach { parts.addLast(it) }
            decoded.trimStart('/').split('/').forEach {
                when (it) { "", "." -> {}; ".." -> parts.removeLastOrNull(); else -> parts.addLast(it) }
            }
            return parts.joinToString("/")
        }
    }
}

object BookLoader {
    fun open(format: String, file: File, title: String): BookSource =
        if (format == "epub") EpubBook(file) else TxtParser.source(file, title)
}

/** 简体 → 繁体显示（Android 10+ 的 ICU 转写器，逐字转换，长度基本不变） */
class TraditionalSource(private val base: BookSource) : BookSource {
    private val tr = android.icu.text.Transliterator.getInstance("Hans-Hant")
    private fun c(s: String): String = runCatching { tr.transliterate(s) }.getOrDefault(s).let { if (it.length == s.length) it else s }
    override val chapterTitles: List<String> by lazy { base.chapterTitles.map(::c) }
    override fun loadChapter(index: Int): Chapter {
        val ch = base.loadChapter(index)
        return Chapter(c(ch.title), ch.paragraphs.map { if (it.startsWith(IMG_PREFIX)) it else c(it) }, ch.images)
    }
    override fun image(path: String) = base.image(path)
}

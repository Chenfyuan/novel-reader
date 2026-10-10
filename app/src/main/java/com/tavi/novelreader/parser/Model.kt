package com.tavi.novelreader.parser

import org.json.JSONArray
import org.json.JSONObject

const val IMG_PREFIX = "\uE000IMG:"
const val OBJ = '\uFFFC'

/** 行内样式位 */
object St {
    const val BOLD = 1; const val ITALIC = 2; const val UNDERLINE = 4; const val STRIKE = 8
    const val SUP = 16; const val SUB = 32; const val LINK = 64; const val SMALL = 128
}

/** 行内样式区间（相对块文本） */
data class Span(val start: Int, val end: Int, val style: Int, val href: String? = null)

enum class BlockKind { PARA, HEADING, QUOTE, LIST, IMAGE, HR, TABLE }

/**
 * 章节中的一个块。
 * - level：标题级别（1–6）/ 列表嵌套深度 / 普通段落的左缩进级别
 * - align：0 = 起始，1 = 居中，2 = 末尾
 */
data class Block(
    val kind: BlockKind,
    val text: String = "",
    val spans: List<Span> = emptyList(),
    val level: Int = 0,
    val align: Int = 0,
    val noIndent: Boolean = false,
    val marker: String? = null,
    val image: String? = null,
    val rows: List<List<String>>? = null,
    val header: Boolean = false,
) {
    val isObject get() = kind == BlockKind.IMAGE || kind == BlockKind.HR || kind == BlockKind.TABLE

    /** 块前缀（纯文本中占位的字符）：段落为两个全角空格，列表为「标记+空格」，对象块无前缀 */
    val prefix: String get() = when (kind) {
        BlockKind.LIST -> (marker ?: "•") + "\u2002"
        BlockKind.IMAGE, BlockKind.HR, BlockKind.TABLE -> ""
        else -> "\u3000\u3000"
    }

    fun plain(): String = if (isObject) OBJ.toString() else prefix + text

    fun toJson(): JSONObject = JSONObject().apply {
        put("k", kind.ordinal)
        if (text.isNotEmpty()) put("x", text)
        if (spans.isNotEmpty()) put("s", JSONArray().apply { spans.forEach { put(it.start); put(it.end); put(it.style) } })
        spans.withIndex().filter { it.value.href != null }.takeIf { it.isNotEmpty() }?.let { l ->
            put("hr", JSONObject().apply { l.forEach { (i, sp) -> put(i.toString(), sp.href) } })
        }
        if (level != 0) put("l", level)
        if (align != 0) put("a", align)
        if (noIndent) put("n", true)
        marker?.let { put("m", it) }
        image?.let { put("i", it) }
        rows?.let { r -> put("r", JSONArray().apply { r.forEach { row -> put(JSONArray(row)) } }) }
        if (header) put("h", true)
    }

    companion object {
        fun fromJson(o: JSONObject): Block {
            val s = o.optJSONArray("s")
            val hr = o.optJSONObject("hr")
            val spans = if (s == null) emptyList() else (0 until s.length() / 3).map {
                Span(s.getInt(it * 3), s.getInt(it * 3 + 1), s.getInt(it * 3 + 2), hr?.optString(it.toString())?.takeIf { h -> h.isNotEmpty() })
            }
            val r = o.optJSONArray("r")
            val rows = r?.let { (0 until it.length()).map { i -> it.getJSONArray(i).let { a -> (0 until a.length()).map { j -> a.getString(j) } } } }
            return Block(BlockKind.entries[o.getInt("k")], o.optString("x"), spans, o.optInt("l"), o.optInt("a"),
                o.optBoolean("n"), if (o.has("m")) o.getString("m") else null, if (o.has("i")) o.getString("i") else null, rows, o.optBoolean("h"))
        }
    }
}

class Chapter(
    val title: String,
    val blocks: List<Block>,
    /** 图片路径 -> (宽, 高) */
    val images: Map<String, Pair<Int, Int>> = emptyMap(),
    /** 元素 id -> 块序号（用于内部链接跳转） */
    val anchors: Map<String, Int> = emptyMap(),
    /** EPUB 中的文件路径 */
    val path: String? = null,
) {
    /** 兼容旧接口：图片段落以 IMG_PREFIX 开头 */
    val paragraphs: List<String> get() = blocks.map { if (it.kind == BlockKind.IMAGE) IMG_PREFIX + it.image else it.text }

    fun toJson(): JSONObject = JSONObject().apply {
        put("t", title)
        put("b", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
        if (anchors.isNotEmpty()) put("anc", JSONObject(anchors as Map<*, *>))
        path?.let { put("p", it) }
        if (images.isNotEmpty()) put("img", JSONObject().apply { images.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Chapter {
            val b = o.getJSONArray("b")
            val img = o.optJSONObject("img")
            val images = img?.keys()?.asSequence()?.associateWith { k -> img.getJSONArray(k).let { it.getInt(0) to it.getInt(1) } } ?: emptyMap()
            val anc = o.optJSONObject("anc")
            val anchors = anc?.keys()?.asSequence()?.associateWith { anc.getInt(it) } ?: emptyMap()
            return Chapter(o.getString("t"), (0 until b.length()).map { Block.fromJson(b.getJSONObject(it)) }, images, anchors,
                if (o.has("p")) o.getString("p") else null)
        }
    }
}

/** 由纯文本段落构造章节（TXT） */
fun textChapter(title: String, paragraphs: List<String>, images: Map<String, Pair<Int, Int>> = emptyMap()) =
    Chapter(title, paragraphs.map { if (it.startsWith(IMG_PREFIX)) Block(BlockKind.IMAGE, image = it.removePrefix(IMG_PREFIX)) else Block(BlockKind.PARA, it) }, images)

const val EMPTY_CHAPTER = "\u3000\u3000（本章无文字内容）"

/** 章节纯文本（与阅读页排版字符一一对应），用于进度、搜索、朗读、笔记 */
fun Chapter.plainText(): String = buildString {
    append(title); append('\n')
    blocks.forEachIndexed { i, b ->
        append(b.plain())
        if (i != blocks.lastIndex) append('\n')
    }
    if (blocks.isEmpty()) append(EMPTY_CHAPTER)
}

/** 每个块在章节纯文本中的起始位置 */
fun Chapter.blockStarts(): IntArray {
    val out = IntArray(blocks.size)
    var pos = title.length + 1
    blocks.forEachIndexed { i, b -> out[i] = pos; pos += b.plain().length + 1 }
    return out
}

interface BookSource {
    val chapterTitles: List<String>
    fun loadChapter(index: Int): Chapter
    fun image(path: String): ByteArray? = null
    /** 文件路径 -> 章节序号（内部链接） */
    fun chapterOf(path: String): Int? = null
}

/** 链接在章节纯文本中的位置 */
data class LinkRange(val start: Int, val end: Int, val href: String)

fun Chapter.links(): List<LinkRange> {
    val st = blockStarts()
    val out = mutableListOf<LinkRange>()
    blocks.forEachIndexed { i, b ->
        val base = st[i] + b.prefix.length
        b.spans.forEach { sp -> if (sp.href != null) out += LinkRange(base + sp.start, base + sp.end, sp.href) }
    }
    return out
}

/** 表格文字（用于搜索与朗读） */
fun Block.tableText(): String = rows.orEmpty().joinToString("。") { it.joinToString("，") }

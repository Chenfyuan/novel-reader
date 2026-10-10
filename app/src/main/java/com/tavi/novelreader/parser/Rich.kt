package com.tavi.novelreader.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 解析后的 CSS 声明（只支持安全子集） */
data class CssProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val align: Int? = null,
    val noIndent: Boolean? = null,
    val marginLeft: Boolean? = null,
) {
    fun merge(o: CssProps) = CssProps(o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike,
        o.align ?: align, o.noIndent ?: noIndent, o.marginLeft ?: marginLeft)
}

/** 极简 CSS 解析：支持 tag / .class / tag.class 选择器与 font-weight、font-style、text-decoration、text-align、text-indent、margin-left */
class Css(text: String) {
    private val rules = HashMap<String, CssProps>()

    init {
        val clean = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("@media[^{]*\\{([^{}]*\\{[^{}]*\\})*[^{}]*\\}"), "")
        Regex("([^{}]+)\\{([^{}]*)\\}").findAll(clean).forEach { m ->
            val props = parseDecls(m.groupValues[2])
            m.groupValues[1].split(',').map { it.trim().lowercase() }
                .filter { it.matches(Regex("[a-z0-9]*(\\.[\\w-]+)?")) && it.isNotEmpty() }
                .forEach { sel -> rules[sel] = (rules[sel] ?: CssProps()).merge(props) }
        }
    }

    fun forElement(e: Element): CssProps {
        val tag = e.normalName()
        var p = rules[tag] ?: CssProps()
        for (c in e.classNames()) {
            val c2 = c.lowercase()
            rules[".$c2"]?.let { p = p.merge(it) }
            rules["$tag.$c2"]?.let { p = p.merge(it) }
            // 常见的居中类名
            if (c2 in CENTER_CLASSES) p = p.merge(CssProps(align = 1))
        }
        val inline = e.attr("style")
        if (inline.isNotEmpty()) p = p.merge(parseDecls(inline))
        if (e.hasAttr("align")) parseAlign(e.attr("align"))?.let { p = p.merge(CssProps(align = it)) }
        return p
    }

    companion object {
        private val CENTER_CLASSES = setOf("center", "centered", "align-center", "aligncenter", "text-center", "tc", "c")

        private fun parseAlign(v: String) = when (v.trim().lowercase()) {
            "center" -> 1; "right", "end" -> 2; "left", "start", "justify" -> 0; else -> null
        }

        private fun length(v: String): Float? {
            val m = Regex("(-?[0-9.]+)\\s*(em|px|%|pt|rem)?").find(v.trim()) ?: return null
            return m.groupValues[1].toFloatOrNull()
        }

        fun parseDecls(s: String): CssProps {
            var p = CssProps()
            s.split(';').forEach { d ->
                val i = d.indexOf(':'); if (i < 0) return@forEach
                val k = d.substring(0, i).trim().lowercase()
                val v = d.substring(i + 1).replace("!important", "").trim().lowercase()
                p = when (k) {
                    "font-weight" -> p.copy(bold = v == "bold" || v == "bolder" || (v.toIntOrNull() ?: 400) >= 600)
                    "font-style" -> p.copy(italic = v == "italic" || v == "oblique")
                    "text-decoration", "text-decoration-line" -> p.copy(underline = "underline" in v, strike = "line-through" in v)
                    "text-align" -> parseAlign(v)?.let { p.copy(align = it) } ?: p
                    "text-indent" -> length(v)?.let { p.copy(noIndent = it <= 0f) } ?: p
                    "margin-left", "padding-left" -> length(v)?.let { p.copy(marginLeft = it > 0f) } ?: p
                    else -> p
                }
            }
            return p
        }
    }
}

/** 把 XHTML 转为样式化的块 */
class RichConverter(private val css: Css, private val resolveImage: (String) -> String?) {
    private val out = mutableListOf<Block>()
    private val sb = StringBuilder()
    private val spans = mutableListOf<Span>()

    private data class Ctx(
        val style: Int = 0, val align: Int = 0, val noIndent: Boolean = false, val indent: Int = 0,
        val heading: Int = 0, val quote: Int = 0, val listDepth: Int = 0,
    )

    // 当前 li 的标记，只给第一个块使用
    private var pendingMarker: String? = null
    private var curCtx = Ctx()

    fun convert(doc: Document): List<Block> {
        doc.body()?.let { walk(it, Ctx()) }
        flush()
        return out
    }

    private fun append(text: String, style: Int) {
        val t = text.replace(Regex("[\\s\\u00A0]+"), " ")
        if (t.isEmpty()) return
        val s = if (sb.isEmpty() || sb.last() == ' ') t.trimStart() else t
        if (s.isEmpty()) return
        val start = sb.length
        sb.append(s)
        if (style != 0) {
            val last = spans.lastOrNull()
            if (last != null && last.end == start && last.style == style) spans[spans.lastIndex] = last.copy(end = sb.length)
            else spans += Span(start, sb.length, style)
        }
    }

    private fun flush() {
        // 去除首尾空白并修正样式区间
        var lead = 0
        while (lead < sb.length && sb[lead] == ' ') lead++
        var end = sb.length
        while (end > lead && sb[end - 1] == ' ') end--
        if (end > lead) {
            val text = sb.substring(lead, end)
            val sp = spans.mapNotNull { s ->
                val a = (s.start - lead).coerceIn(0, text.length); val b = (s.end - lead).coerceIn(0, text.length)
                if (b > a) Span(a, b, s.style) else null
            }
            val c = curCtx
            out += when {
                c.heading > 0 -> Block(BlockKind.HEADING, text, sp, level = c.heading, align = c.align)
                c.listDepth > 0 -> Block(BlockKind.LIST, text, sp, level = c.listDepth, marker = pendingMarker ?: "", align = c.align)
                    .also { pendingMarker = null }
                c.quote > 0 -> Block(BlockKind.QUOTE, text, sp, level = c.quote, align = c.align)
                else -> Block(BlockKind.PARA, text, sp, level = c.indent, align = c.align, noIndent = c.noIndent)
            }
        }
        sb.clear(); spans.clear()
    }

    private fun walk(n: Node, ctx: Ctx) {
        when (n) {
            is TextNode -> append(n.wholeText, ctx.style)
            is Element -> element(n, ctx)
        }
    }

    private fun element(e: Element, parent: Ctx) {
        val tag = e.normalName()
        if (tag in SKIP) return
        val p = css.forElement(e)
        var style = parent.style
        when (tag) {
            "b", "strong" -> style = style or St.BOLD
            "i", "em", "cite", "dfn", "var" -> style = style or St.ITALIC
            "u", "ins" -> style = style or St.UNDERLINE
            "s", "strike", "del" -> style = style or St.STRIKE
            "sup" -> style = style or St.SUP
            "sub" -> style = style or St.SUB
            "small" -> style = style or St.SMALL
            "a" -> if (e.hasAttr("href")) style = style or St.LINK
        }
        p.bold?.let { style = if (it) style or St.BOLD else style and St.BOLD.inv() }
        p.italic?.let { style = if (it) style or St.ITALIC else style and St.ITALIC.inv() }
        if (p.underline == true) style = style or St.UNDERLINE
        if (p.strike == true) style = style or St.STRIKE

        var ctx = parent.copy(style = style, align = p.align ?: parent.align, noIndent = p.noIndent ?: parent.noIndent)

        when (tag) {
            "img", "image" -> {
                flush()
                val src = e.attr("src").ifEmpty { e.attr("xlink:href") }.ifEmpty { e.attr("href") }
                resolveImage(src)?.let { out += Block(BlockKind.IMAGE, image = it) }
                return
            }
            "hr" -> { flush(); out += Block(BlockKind.HR); return }
            "br" -> { flush(); return }
            "table" -> {
                flush()
                val rows = e.select("tr").map { tr -> tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }.map { it.text().trim() } }
                    .filter { r -> r.any { it.isNotEmpty() } }
                if (rows.isNotEmpty()) out += Block(BlockKind.TABLE, rows = rows,
                    header = e.selectFirst("tr")?.children()?.any { it.normalName() == "th" } == true || e.selectFirst("thead") != null)
                return
            }
            "ruby" -> {
                // 注音：基字后以小号括号显示读音
                for (c in e.childNodes()) {
                    if (c is Element && c.normalName() == "rt") append("(" + c.text() + ")", style or St.SMALL)
                    else if (c is Element && c.normalName() == "rp") continue
                    else walk(c, ctx)
                }
                return
            }
        }

        val block = tag in BLOCKS
        if (block) {
            flush()
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> ctx = ctx.copy(heading = tag[1] - '0')
                "blockquote" -> ctx = ctx.copy(quote = ctx.quote + 1)
                "center" -> ctx = ctx.copy(align = 1)
            }
            if (p.marginLeft == true && ctx.heading == 0 && ctx.listDepth == 0 && ctx.quote == 0) ctx = ctx.copy(indent = (ctx.indent + 1).coerceAtMost(3))
            curCtx = ctx
        }
        if (tag == "ul" || tag == "ol") {
            flush()
            val depth = ctx.listDepth + 1
            var n = e.attr("start").toIntOrNull() ?: 1
            for (c in e.children()) {
                if (c.normalName() != "li") { walk(c, ctx.copy(listDepth = depth)); continue }
                flush()
                pendingMarker = if (tag == "ol") "${n++}." else if (depth % 2 == 1) "•" else "◦"
                val liCtx = ctx.copy(listDepth = depth)
                curCtx = liCtx
                c.childNodes().forEach { walk(it, liCtx) }
                flush()
                pendingMarker = null
            }
            curCtx = parent
            return
        }
        e.childNodes().forEach { walk(it, ctx) }
        if (block) { flush(); curCtx = parent }
    }

    companion object {
        private val SKIP = setOf("script", "style", "head", "title", "rt", "rp", "noscript")
        private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "article",
            "header", "footer", "aside", "pre", "dt", "dd", "dl", "figure", "figcaption", "center", "nav", "main", "address")
    }
}

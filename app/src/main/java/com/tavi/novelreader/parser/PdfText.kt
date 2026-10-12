package com.tavi.novelreader.parser

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * PDF 文字层（pdfbox-android）：逐页提取文字，用于选择文字、全文搜索和朗读。
 * 扫描版 PDF 没有文字层，提取结果为空，由界面提示。
 */
class PdfText(ctx: Context, file: File) : Closeable {
    init { PDFBoxResourceLoader.init(ctx.applicationContext) }
    private val doc: PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
    private val cache = ConcurrentHashMap<Int, String>()
    val count = doc.numberOfPages

    /** 第 i 页文字（已整理空白），无文字层时为空串 */
    fun page(i: Int): String = cache[i] ?: synchronized(this) {
        cache[i] ?: run {
            val raw = runCatching {
                PDFTextStripper().apply { startPage = i + 1; endPage = i + 1; sortByPosition = true }.getText(doc)
            }.getOrDefault("")
            tidy(raw).also { cache[i] = it }
        }
    }

    /** 抽查前若干页判断是否有文字层 */
    fun hasTextLayer(): Boolean = (0 until minOf(count, 8)).any { page(it).isNotBlank() }

    override fun close() { runCatching { doc.close() } }

    companion object {
        /** 合并 PDF 的硬换行：CJK 之间直接拼接，西文之间补空格，连字符断词还原；空行保留为分段 */
        fun tidy(raw: String): String {
            val paras = raw.replace("\r", "").split(Regex("\n\\s*\n"))
            return paras.joinToString("\n") { p ->
                val lines = p.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val sb = StringBuilder()
                for (l in lines) {
                    if (sb.isEmpty()) { sb.append(l); continue }
                    val a = sb.last(); val b = l.first()
                    when {
                        a == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && b.isLowerCase() -> sb.setLength(sb.length - 1)
                        isCjk(a) || isCjk(b) -> {}
                        else -> sb.append(' ')
                    }
                    sb.append(l)
                }
                sb.toString()
            }.trim()
        }
        private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code).let {
            it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
        } || c in '\u3000'..'\u303f' || c in '\uff00'..'\uffef'
    }
}

/** 把 PDF 每页作为一「章」交给朗读引擎 */
class PdfTextSource(private val text: PdfText) : BookSource {
    override val chapterTitles: List<String> = List(text.count) { "第 ${it + 1} 页" }
    override fun loadChapter(index: Int): Chapter {
        val t = text.page(index)
        // 标题留空，避免每页都朗读「第 n 页」
        return textChapter("", if (t.isBlank()) listOf(" ") else t.split('\n'))
    }
}

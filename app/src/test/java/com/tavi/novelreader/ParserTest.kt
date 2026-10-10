package com.tavi.novelreader

import com.tavi.novelreader.parser.*
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ParserTest {
    @Test fun richBlocks() {
        val html = """<html><head><style>.c{text-align:center}</style></head><body>
            <h2>标题</h2><p class="c">居中</p><p>普通<b>粗</b><i>斜</i>H<sub>2</sub>O</p>
            <blockquote><p>引用</p></blockquote><ol><li>一</li><li>二</li></ol><hr/>
            <table><tr><th>a</th><th>b</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>"""
        val doc = Jsoup.parse(html)
        val b = RichConverter(Css(doc.select("style").first()!!.data())) { null }.convert(doc)
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARA, BlockKind.PARA, BlockKind.QUOTE, BlockKind.LIST, BlockKind.LIST, BlockKind.HR, BlockKind.TABLE), b.map { it.kind })
        assertEquals(2, b[0].level)
        assertEquals(1, b[1].align)
        assertEquals("普通粗斜H2O", b[2].text)
        assertEquals(listOf(Span(2, 3, St.BOLD), Span(3, 4, St.ITALIC), Span(5, 6, St.SUB)), b[2].spans)
        assertEquals("2.", b[5].marker)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), b[7].rows)
        // JSON 往返
        val ch = Chapter("t", b)
        assertEquals(ch.plainText(), Chapter.fromJson(ch.toJson()).plainText())
        // 块起点与纯文本一致
        val t = ch.plainText(); val st = ch.blockStarts()
        b.forEachIndexed { i, blk -> assertEquals(blk.plain(), t.substring(st[i], st[i] + blk.plain().length)) }
    }

    @Test fun txtStreamingAndRemap() {
        val f = File.createTempFile("txt-test", ".txt")
        f.writeText("前言内容\n第一章 开始\n第一段\n第二段\n第二章 继续\n第三段\n", Charsets.UTF_8)
        val dir = File(f.parentFile, "cache-test-" + System.nanoTime())
        val lens = TxtImporter.import(f, BookCache.Writer(dir))
        val src = CachedSource(dir)
        assertEquals(listOf("第 1 部分", "第一章 开始", "第二章 继续"), src.chapterTitles)
        assertEquals(lens, src.lens)
        assertEquals("第三段", src.loadChapter(2).blocks[0].text)
        // GB18030
        val g = File.createTempFile("gbk-test", ".txt"); g.writeBytes("第一章 测试\n中文内容".toByteArray(charset("GB18030")))
        assertEquals("GB18030", TxtImporter.detectCharset(g).name())
        // 位置映射
        assertEquals(1 to 5, BookStore.remap(listOf(10, 10), listOf(10, 10), 1, 5))
        assertEquals(2 to 10, BookStore.remap(listOf(20, 20), listOf(10, 10, 20), 1, 10))
        dir.deleteRecursively(); f.delete(); g.delete()
    }
}

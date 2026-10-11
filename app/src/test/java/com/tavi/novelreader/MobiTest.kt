package com.tavi.novelreader

import com.tavi.novelreader.parser.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MobiTest {
    private fun u16(o: ByteArrayOutputStream, v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
    private fun u32(o: ByteArrayOutputStream, v: Int) { u16(o, v ushr 16); u16(o, v and 0xFFFF) }

    /** 只用字面量编码的 PalmDOC 压缩（足以验证解压与记录拼接） */
    private fun literal(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        b.forEach { x -> val v = x.toInt() and 0xFF; if (v == 0 || v in 0x09..0x7F) o.write(v) else { o.write(1); o.write(v) } }
    }.toByteArray()

    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    private fun buildMobi(html: String, kf8: Boolean, encryption: Int = 0, junkBoundary: Boolean = false): File {
        val text = html.toByteArray()
        val chunks = text.toList().chunked(4096).map { literal(it.toByteArray()) }
        val nText = chunks.size
        val fdstIndex = nText + 1
        val firstImage = if (kf8) nText + 2 else nText + 1
        val r0 = ByteArrayOutputStream().apply {
            u16(this, 2); u16(this, 0); u32(this, text.size); u16(this, nText); u16(this, 4096); u16(this, encryption); u16(this, 0)
            val m = ByteArray(232); "MOBI".toByteArray().copyInto(m, 0)
            fun put32(off: Int, v: Int) { m[off - 16] = (v ushr 24).toByte(); m[off - 15] = (v ushr 16).toByte(); m[off - 14] = (v ushr 8).toByte(); m[off - 13] = v.toByte() }
            put32(20, 232); put32(24, 2); put32(28, 65001); put32(36, if (kf8) 8 else 6)
            put32(108, firstImage); put32(128, 0x40); if (kf8) put32(192, fdstIndex)
            write(m)
            val exth = ByteArrayOutputStream()
            val title = "测试之书".toByteArray(); val author = "某作者".toByteArray()
            u32(exth, 503); u32(exth, 8 + title.size); exth.write(title)
            u32(exth, 100); u32(exth, 8 + author.size); exth.write(author)
            u32(exth, 201); u32(exth, 12); u32(exth, 0)
            if (junkBoundary) { u32(exth, 121); u32(exth, 12); u32(exth, firstImage + 1) }
            write("EXTH".toByteArray()); u32(this, 12 + exth.size()); u32(this, if (junkBoundary) 4 else 3); write(exth.toByteArray())
        }.toByteArray()
        val records = mutableListOf(r0) + chunks +
            (if (kf8) listOf(ByteArrayOutputStream().apply { write("FDST".toByteArray()); u32(this, 12); u32(this, 1); u32(this, 0); u32(this, text.size) }.toByteArray()) else emptyList()) +
            listOf(png) + (if (junkBoundary) listOf("BOUNDARY".toByteArray(), ByteArray(64) { 0xFF.toByte() }) else emptyList())
        val out = ByteArrayOutputStream()
        out.write(ByteArray(60)); out.write("BOOKMOBI".toByteArray()); out.write(ByteArray(8)); u16(out, records.size)
        var off = 78 + records.size * 8 + 2
        records.forEachIndexed { i, r -> u32(out, off); u32(out, i * 2); off += r.size }
        out.write(ByteArray(2))
        records.forEach { out.write(it) }
        return File.createTempFile("mobi-test", ".mobi").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    @Test fun palmDocBackrefs() {
        // "abc" + 回溯 (距离 3，长度 3) + 0xE1 = 空格 + 'a'
        val out = MobiBook.palmDoc(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x18, 0xE1.toByte()))
        assertEquals("abcabc a", String(out))
        // 尾部条目：flags=0b11 → 一个变长条目 + 多字节标记
        val rec = "hello".toByteArray() + byteArrayOf(0x00, 0x82.toByte())
        assertEquals(3, MobiBook.trailingSize(rec, 0b10) + 1)
    }

    @Test fun mobi6WithPagebreaks() {
        val body = (1..3).joinToString("<mbp:pagebreak/>") { i -> "<h2>第${i}章 标题</h2>" + "<p>这是第${i}章的正文，<b>粗体</b>内容。</p>".repeat(20) } +
            "<p><img recindex=\"00001\"/></p>"
        val f = buildMobi("<html><body>$body</body></html>", kf8 = false)
        val m = MobiBook(f)
        assertEquals("测试之书", m.title); assertEquals("某作者", m.author); assertFalse(m.isKf8)
        assertNotNull(m.coverBytes())
        val chs = m.chapters({ n -> "cache:img/$n" }, { null })
        assertEquals(listOf("第1章 标题", "第2章 标题", "第3章 标题"), chs.map { it.title })
        assertTrue(chs[0].blocks.any { b -> b.spans.any { it.style and St.BOLD != 0 } })
        assertTrue(chs.last().blocks.any { it.kind == BlockKind.IMAGE && it.image == "cache:img/0" })
    }

    @Test fun kf8FlowsAndEmbeds() {
        val doc = { t: String -> "<?xml version=\"1.0\"?><html><head><style>.c{text-align:center}</style></head><body><h1>$t</h1>" +
            "<p class=\"c\">居中的一行</p>" + "<p>正文段落，用于测试 KF8 文本流。</p>".repeat(15) +
            "<img src=\"kindle:embed:0001?mime=image/png\"/></body></html>" }
        val f = buildMobi(doc("序章") + doc("第一章"), kf8 = true)
        val m = MobiBook(f)
        assertTrue(m.isKf8)
        val chs = m.chapters({ n -> "cache:img/$n" }, { null })
        assertEquals(listOf("序章", "第一章"), chs.map { it.title })
        assertTrue(chs[0].blocks.any { it.align == 1 })
        assertTrue(chs[0].blocks.any { it.kind == BlockKind.IMAGE })
    }

    @Test fun kf8BoundaryJunkIsNotDrm() {
        // EXTH 121 指向 BOUNDARY 后的无效段头（加密字段非 0）：不能判为 DRM，应退回 MOBI6 内容
        val f = buildMobi("<html><body><h2>第一章</h2><p>${"正文".repeat(200)}</p></body></html>", kf8 = false, junkBoundary = true)
        val m = MobiBook(f)
        assertEquals("第一章", m.chapters({ null }, { null }).first().title)
    }

    @Test fun encryptionFlagDoesNotPreBlock() {
        val f = buildMobi("<html><body><p>x</p></body></html>", kf8 = false, encryption = 2)
        assertEquals("测试之书", MobiBook(f).title)
    }

    @Test fun naturalSort() {
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg", "p10b.jpg"), listOf("p10.jpg", "p2.jpg", "p10b.jpg", "p1.jpg").sortedWith(NaturalOrder))
    }

    /** 若本机有真实文件（Project Gutenberg 的 Alice），检查能解析出合理的章节 */
    @Test fun realFilesIfPresent() {
        for (name in listOf("alice.azw3", "alice.mobi")) {
            val f = File("/tmp/realbooks/$name")
            assumeTrue(f.exists())
            val m = MobiBook(f)
            val chs = m.chapters({ n -> m.resource(n)?.let { "cache:img/$n" } }, { null })
            val text = chs.joinToString("\n") { it.plainText() }
            println("$name: kf8=${m.isKf8} title=${m.title} author=${m.author} chapters=${chs.size} chars=${text.length} cover=${m.coverBytes()?.size}")
            println(chs.take(8).joinToString(" | ") { it.title })
            assertTrue(text.contains("Alice"))
            assertTrue(chs.size >= 5)
        }
    }
}

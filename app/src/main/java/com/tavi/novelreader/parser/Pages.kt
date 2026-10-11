package com.tavi.novelreader.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** 按页显示图片的书（PDF、漫画） */
interface PageSource : Closeable {
    val count: Int
    /** 渲染第 i 页，宽度约为 maxWidth 像素 */
    fun render(i: Int, maxWidth: Int): Bitmap?
}

/** PDF：使用系统 PdfRenderer 渲染为位图（同一时间只能打开一页，因此加锁） */
class PdfPages(file: File) : PageSource {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    override val count = renderer.pageCount

    override fun render(i: Int, maxWidth: Int): Bitmap? = synchronized(this) {
        if (i !in 0 until count) return null
        renderer.openPage(i).use { p ->
            val scale = (maxWidth.toFloat() / p.width).coerceIn(0.5f, 4f)
            val w = (p.width * scale).toInt().coerceAtLeast(1); val h = (p.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    /** 每页宽高比（用于占位） */
    fun aspect(i: Int): Float = synchronized(this) { renderer.openPage(i).use { it.width.toFloat() / it.height } }

    override fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }
}

/** CBZ / ZIP 漫画：压缩包内的图片按自然顺序排列 */
class ComicPages(file: File) : PageSource {
    private val zip = ZipFile(file)
    val entries: List<String> = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXT && !it.name.startsWith("__MACOSX") }
        .map { it.name }.sortedWith(NaturalOrder).toList()
    override val count = entries.size

    init { if (entries.isEmpty()) { zip.close(); error("压缩包中没有图片") } }

    fun bytes(i: Int): ByteArray? = entries.getOrNull(i)?.let { n -> zip.getInputStream(zip.getEntry(n)).use { it.readBytes() } }

    override fun render(i: Int, maxWidth: Int): Bitmap? {
        val b = bytes(i) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    override fun close() = zip.close()

    companion object { val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif") }
}

/** 自然排序：page2 < page10 */
object NaturalOrder : Comparator<String> {
    private val re = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = re.findAll(a.lowercase()).map { it.value }.toList(); val y = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val d = p.trimStart('0').length.compareTo(q.trimStart('0').length)
                if (d != 0) d else p.trimStart('0').compareTo(q.trimStart('0'))
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

fun openPages(format: String, file: File): PageSource = if (format == "pdf") PdfPages(file) else ComicPages(file)

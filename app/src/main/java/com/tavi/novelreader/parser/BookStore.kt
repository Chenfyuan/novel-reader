package com.tavi.novelreader.parser

import java.io.File

/** 构建 / 打开解析缓存 */
object BookStore {
    /** 解析整本书写入缓存，返回每章字数 */
    fun build(root: File, format: String, file: File, onProgress: (Float) -> Unit = {}): List<Int> {
        val dir = BookCache.dirFor(root, file.path)
        val w = BookCache.Writer(dir)
        if (format == "mobi") {
            val m = MobiBook(file)
            val imgDir = File(dir, "img").apply { mkdirs() }
            val dims = HashMap<String, Pair<Int, Int>>()
            onProgress(0.1f)
            val chapters = m.chapters({ n ->
                val bytes = m.resource(n) ?: return@chapters null
                val f = File(imgDir, "$n"); if (!f.exists()) f.writeBytes(bytes)
                val path = "cache:img/$n"
                imageSize(bytes)?.let { dims[path] = it }
                path
            }, { dims[it] })
            chapters.forEachIndexed { i, ch -> w.add(ch); onProgress(0.1f + 0.9f * (i + 1) / chapters.size) }
            return w.finish()
        }
        return if (format == "epub") {
            val epub = EpubBook(file)
            val n = epub.chapterTitles.size
            for (i in 0 until n) {
                val ch = runCatching { epub.loadChapter(i) }.getOrElse { textChapter(epub.chapterTitles[i], listOf("本章解析失败：${it.message}")) }
                w.add(ch)
                onProgress((i + 1f) / n)
            }
            w.finish()
        } else TxtImporter.import(file, w, onProgress)
    }

    /** 图片尺寸（只解码边界） */
    fun imageSize(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }.getOrNull()

    /** 根据文件名 / MIME 判断格式 */
    fun formatOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "epub" || mime == "application/epub+zip" -> "epub"
            ext in setOf("mobi", "azw3", "azw", "prc") || mime == "application/x-mobipocket-ebook" -> "mobi"
            ext == "pdf" || mime == "application/pdf" -> "pdf"
            ext in setOf("cbz", "zip") || mime == "application/vnd.comicbook+zip" || mime == "application/zip" -> "cbz"
            else -> "txt"
        }
    }

    fun open(root: File, format: String, file: File): CachedSource? {
        val dir = BookCache.dirFor(root, file.path)
        if (!BookCache.isValid(dir)) return null
        return CachedSource(dir, if (format == "epub") runCatching { EpubBook(file) }.getOrNull() else null)
    }

    /** 按全书比例把旧位置映射到新的章节结构 */
    fun remap(oldLens: List<Int>, newLens: List<Int>, ch: Int, off: Int): Pair<Int, Int> {
        if (newLens.isEmpty()) return 0 to 0
        if (oldLens.isEmpty() || oldLens.sum() == 0) return ch.coerceIn(0, newLens.lastIndex) to 0
        if (oldLens == newLens) return ch to off
        val c = ch.coerceIn(0, oldLens.lastIndex)
        val frac = (oldLens.take(c).sum() + off.coerceIn(0, oldLens[c])).toDouble() / oldLens.sum()
        var target = (frac * newLens.sum()).toLong()
        for (i in newLens.indices) {
            if (target < newLens[i] || i == newLens.lastIndex) return i to target.toInt().coerceIn(0, newLens[i])
            target -= newLens[i]
        }
        return newLens.lastIndex to 0
    }
}

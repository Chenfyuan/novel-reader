package com.tavi.novelreader.parser

import java.io.File

/** 构建 / 打开解析缓存 */
object BookStore {
    /** 解析整本书写入缓存，返回每章字数 */
    fun build(root: File, format: String, file: File, onProgress: (Float) -> Unit = {}): List<Int> {
        val w = BookCache.Writer(BookCache.dirFor(root, file.path))
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

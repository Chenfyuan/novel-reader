package com.tavi.novelreader.parser

import java.io.File

/** 按文件头识别真实格式 */
object Drm {

    /** 根据文件头识别真实格式；无法识别时返回 null（沿用扩展名 / MIME） */
    fun sniff(file: File): String? {
        val head = ByteArray(80)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "%PDF") return "pdf"
        if (n >= 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }) return "mobi"
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return runCatching {
                java.util.zip.ZipFile(file).use { z ->
                    val mt = z.getEntry("mimetype")?.let { e -> z.getInputStream(e).use { String(it.readBytes()).trim() } }
                    when {
                        mt == "application/epub+zip" || z.getEntry("META-INF/container.xml") != null -> "epub"
                        z.entries().asSequence().any { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in ComicPages.IMAGE_EXT } -> "cbz"
                        else -> null
                    }
                }
            }.getOrNull()
        }
        return null
    }
}

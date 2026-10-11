package com.tavi.novelreader.parser

import java.io.File

/** DRM 与格式识别。只在确有证据时判定为 DRM，避免误报。 */
object Drm {
    /** 字体混淆算法：不是 DRM，直接忽略（字体回退到系统字体） */
    val OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
    private val CONTENT_EXT = setOf("xhtml", "html", "htm", "xml", "opf", "ncx", "txt", "css", "smil")

    /**
     * EPUB：返回 DRM 原因，null 表示可以打开。
     * - Readium LCP（license.lcpl）、Apple FairPlay（sinf.xml）、Adobe ADEPT（rights.xml 且含 adept 命名空间）
     * - encryption.xml 中用非混淆算法加密了正文类文件（xhtml/html/opf 等）
     * 仅加密 / 混淆字体或图片不算 DRM。
     */
    fun epubReason(entries: Set<String>, read: (String) -> String?): String? {
        if ("META-INF/license.lcpl" in entries) return "Readium LCP"
        if ("META-INF/sinf.xml" in entries) return "Apple FairPlay"
        if ("META-INF/rights.xml" in entries && read("META-INF/rights.xml")?.contains("adept", ignoreCase = true) == true) return "Adobe ADEPT"
        val enc = read("META-INF/encryption.xml") ?: return null
        val blocks = Regex("<(?:\\w+:)?EncryptedData\\b[\\s\\S]*?</(?:\\w+:)?EncryptedData>").findAll(enc)
        for (b in blocks) {
            val alg = Regex("<(?:\\w+:)?EncryptionMethod[^>]*Algorithm\\s*=\\s*[\"']([^\"']+)[\"']").find(b.value)?.groupValues?.get(1) ?: continue
            if (alg in OBFUSCATION) continue
            val uri = Regex("<(?:\\w+:)?CipherReference[^>]*URI\\s*=\\s*[\"']([^\"']+)[\"']").find(b.value)?.groupValues?.get(1) ?: continue
            val ext = uri.substringBefore('#').substringAfterLast('.', "").lowercase()
            if (ext in CONTENT_EXT) return if (enc.contains("adept", true)) "Adobe ADEPT" else "正文已加密（$alg）"
        }
        return null
    }

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

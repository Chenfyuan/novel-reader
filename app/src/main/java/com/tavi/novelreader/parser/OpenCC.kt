package com.tavi.novelreader.parser

import java.io.InputStream

enum class ZhVariant(val label: String) { S2T("繁体"), S2TW("台湾正体"), S2HK("香港繁体") }

/**
 * 基于 OpenCC 词典的简繁转换：按词组正向最长匹配（STPhrases + STCharacters），
 * 再按地区做词汇 / 异体字转换。
 * 阅读器依赖「转换前后字符一一对应」，所以只采用长度不变的词条；长度变化的词条退回逐字转换。
 */
class OpenCC(open: (String) -> InputStream, val variant: ZhVariant) {
    private class Dict(val map: HashMap<String, String>, val maxLen: Int)

    private fun load(open: (String) -> InputStream, vararg names: String): Dict {
        val map = HashMap<String, String>()
        var max = 1
        for (n in names) open("opencc/$n.txt").bufferedReader().useLines { lines ->
            lines.forEach { l ->
                if (l.isEmpty() || l[0] == '#') return@forEach
                val tab = l.indexOf('\t'); if (tab <= 0) return@forEach
                val k = l.substring(0, tab)
                val v = l.substring(tab + 1).substringBefore(' ')
                if (v.length != k.length) return@forEach
                map.putIfAbsent(k, v)
                if (k.length > max) max = k.length
            }
        }
        return Dict(map, max)
    }

    private val st = load(open, "STPhrases", "STCharacters")
    private val region: List<Dict> = when (variant) {
        ZhVariant.S2T -> emptyList()
        ZhVariant.S2TW -> listOf(load(open, "TWPhrases"), load(open, "TWVariants"))
        ZhVariant.S2HK -> listOf(load(open, "HKVariants"))
    }

    private fun apply(d: Dict, s: String): String {
        if (s.isEmpty()) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var matched = false
            var len = minOf(d.maxLen, s.length - i)
            while (len > 0) {
                val v = d.map[s.substring(i, i + len)]
                if (v != null) { out.append(v); i += len; matched = true; break }
                len--
            }
            if (!matched) { out.append(s[i]); i++ }
        }
        return out.toString()
    }

    fun convert(s: String): String {
        var r = apply(st, s)
        for (d in region) r = apply(d, r)
        return if (r.length == s.length) r else s
    }

    companion object {
        @Volatile private var cached: OpenCC? = null
        fun get(open: (String) -> InputStream, v: ZhVariant): OpenCC =
            cached?.takeIf { it.variant == v } ?: synchronized(this) { OpenCC(open, v).also { cached = it } }
    }
}

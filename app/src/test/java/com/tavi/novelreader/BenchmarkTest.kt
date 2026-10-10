package com.tavi.novelreader

import com.tavi.novelreader.parser.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** 大文件基准：./gradlew testDebugUnitTest -Pbench --tests '*BenchmarkTest*' */
@RunWith(RobolectricTestRunner::class)
class BenchmarkTest {
    @Test fun txt50mb() {
        assumeTrue(System.getProperty("bench") != null)
        val f = File(System.getProperty("java.io.tmpdir"), "bench-50mb.txt")
        if (!f.exists() || f.length() < 50L * 1024 * 1024) {
            val words = "天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁律吕调阳云腾致雨露结为霜金生丽水玉出昆冈剑号巨阙珠称夜光"
            val rnd = java.util.Random(1)
            f.bufferedWriter().use { w ->
                var ch = 1; var bytes = 0L
                while (bytes < 50L * 1024 * 1024) {
                    val head = "第${ch++}章 测试章节\n"; w.write(head); bytes += head.toByteArray().size
                    repeat(60) {
                        val sb = StringBuilder("　　")
                        repeat(80 + rnd.nextInt(60)) { sb.append(words[rnd.nextInt(words.length)]) }
                        sb.append("。\n"); w.write(sb.toString()); bytes += sb.length * 3L
                    }
                }
            }
        }
        val mb = f.length() / 1024.0 / 1024.0
        fun t(block: () -> Unit): Long { System.gc(); val s = System.nanoTime(); block(); return (System.nanoTime() - s) / 1_000_000 }
        repeat(2) {
            // 旧：导入 = 整文件读入 + 解码 + 解析；每次打开都重复一遍
            var oldN = 0
            val oldImport = t { oldN = TxtParser.parse(TxtParser.decode(f.readBytes()), "b").size }
            val oldOpen = t { val s = BookLoader.open("txt", f, "b"); s.loadChapter(s.chapterTitles.size / 2).plainText() }
            // 新：流式导入写缓存；打开只读索引 + 当前章
            val dir = File(f.parentFile, "bench-cache")
            var newN = 0
            val newImport = t { newN = TxtImporter.import(f, BookCache.Writer(dir)).size }
            val newOpen = t { val s = CachedSource(dir); s.loadChapter(s.chapterTitles.size / 2).plainText() }
            val next = t { val s = CachedSource(dir); s.loadChapter(s.chapterTitles.size / 2 + 1) }
            println("BENCH run=$it file=${"%.1f".format(mb)}MB chapters old=$oldN new=$newN | oldImport=${oldImport}ms oldOpen=${oldOpen}ms | newImport=${newImport}ms newOpen=${newOpen}ms nextChapter=${next}ms")
        }
    }
}

package com.tavi.novelreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.tavi.novelreader.data.*
import com.tavi.novelreader.parser.*
import org.jsoup.Jsoup
import com.tavi.novelreader.ui.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import com.tavi.novelreader.data.PageEffect
import com.tavi.novelreader.ui.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asImageBitmap
import com.tavi.novelreader.ui.*
import com.tavi.novelreader.parser.textChapter
private val OUT = System.getProperty("shots.dir") ?: "build/screenshots"

/** 生成一张示意页面图（PDF / 漫画截图测试用） */
internal fun fakePage(i: Int, w: Int, comic: Boolean): androidx.compose.ui.graphics.ImageBitmap {
    val h = (w * 1.41f).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    if (comic) {
        val m = w * 0.04f
        val cells = listOf(floatArrayOf(0f, 0f, 1f, 0.38f), floatArrayOf(0f, 0.38f, 0.5f, 0.7f), floatArrayOf(0.5f, 0.38f, 1f, 0.7f), floatArrayOf(0f, 0.7f, 1f, 1f))
        val tones = listOf(0xFFB0BEC5, 0xFFCFD8DC, 0xFF90A4AE, 0xFFECEFF1)
        cells.forEachIndexed { k, r ->
            p.style = android.graphics.Paint.Style.FILL; p.color = tones[(k + i) % 4].toInt()
            val rect = android.graphics.RectF(m + r[0] * (w - 2 * m) + 6, m + r[1] * (h - 2 * m) + 6, m + r[2] * (w - 2 * m) - 6, m + r[3] * (h - 2 * m) - 6)
            c.drawRect(rect, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = w / 200f; p.color = android.graphics.Color.BLACK
            c.drawRect(rect, p)
        }
        p.style = android.graphics.Paint.Style.FILL; p.textSize = w / 14f; p.color = android.graphics.Color.BLACK
        c.drawText("${i + 1}", w - m - p.textSize, h - m - 8, p)
    } else {
        p.color = android.graphics.Color.BLACK; p.textSize = w / 18f; p.isFakeBoldText = true
        c.drawText("第 ${i + 1} 章  宇宙背景辐射", w * 0.1f, h * 0.1f, p)
        p.isFakeBoldText = false; p.textSize = w / 34f; p.color = 0xFF333333.toInt()
        var y = h * 0.16f
        repeat(26) { k -> c.drawRect(w * 0.1f, y, w * (if (k % 6 == 5) 0.6f else 0.9f), y + w / 90f, p); y += w / 26f }
        p.color = 0xFF9E9E9E.toInt(); c.drawRect(w * 0.1f, y + 20, w * 0.9f, y + w * 0.4f, p)
    }
    return bmp.asImageBitmap()
}

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val now = 1_760_000_000_000L
    private val books = listOf(
        Book(1, "三体", "刘慈欣", "epub", "", null, 36, 12, 0, 0.342f, now - 9_000_000, now - 60_000, readingMillis = 5_400_000, groupName = "在读", tags = "科幻,雨果奖"),
        Book(2, "活着", "余华", "txt", "", null, 12, 11, 0, 0.98f, now - 8_000_000, now - 3_600_000, readingMillis = 3_000_000, tags = "经典"),
        Book(3, "红楼梦", "曹雪芹", "txt", "", null, 120, 0, 0, 0f, now - 7_000_000, 0, groupName = "在读"),
        Book(4, "百年孤独", "加西亚·马尔克斯", "epub", "", null, 20, 3, 0, 0.151f, now - 6_000_000, now - 86_400_000, readingMillis = 1_200_000),
        Book(5, "围城", "钱钟书", "txt", "", null, 9, 0, 0, 0f, now - 5_000_000, 0),
        Book(6, "平凡的世界（全三部）", "路遥", "txt", "", null, 162, 40, 0, 0.247f, now - 4_000_000, now - 7_200_000, readingMillis = 2_400_000),
        Book(7, "进击的巨人 第 1 卷", "諫山創", "cbz", "", null, 192, 23, 0, 0.12f, now - 3_000_000, now - 600_000),
        Book(8, "深度学习", "Goodfellow 等", "pdf", "", null, 800, 54, 0, 0.068f, now - 2_000_000, now - 1_800_000),
    )
    private val chapter = textChapter("第十二章 宇宙闪烁", List(16) {
        listOf("汪淼回到家时已是深夜，城市的灯火在窗外静静地铺展开来。他没有开灯，只是站在窗前，看着远处高楼顶端闪烁的航空警示灯。",
            "倒计时仍在继续，那串数字像是直接印在了视网膜上，无论睁眼还是闭眼，都清晰可见。他想起了史强的话：“邪乎到家必有鬼。”",
            "第二天一早，他驱车前往郊外的射电天文观测基地。冬日的阳光很淡，田野里覆着一层薄霜，道路两旁的白杨树光秃秃地伸向天空。")[it % 3]
    })

    private fun shot(name: String, dark: Boolean, win: WindowLayout = WindowLayout(expanded = true, medium = true, spread = true), content: @Composable () -> Unit) {
        rule.setContent {
            NovelTheme(darkTheme = dark, dynamicColor = false, settings = ReaderSettings()) {
                CompositionLocalProvider(LocalWindowLayout provides win) {
                    Surface(color = MaterialTheme.colorScheme.surface) { content() }
                }
            }
        }
        repeat(6) { Thread.sleep(250); rule.waitForIdle(); rule.mainClock.advanceTimeBy(400) }
        rule.onRoot().captureRoboImage("$OUT/${name}_${if (dark) "dark" else "light"}.png")
    }

    @Test fun spreadLight() = shot("30_tablet_spread", false) { Spread(ReaderTheme.LIGHT) }
    @Test fun spreadDark() = shot("30_tablet_spread", true) { Spread(ReaderTheme.DARK) }
    @Composable private fun Spread(theme: ReaderTheme) {
        Box(Modifier.fillMaxSize().background(theme.bg)) {
            ChapterPager(chapter, 11, 36, PageLayout(showClock = false), PageEffect.SLIDE, theme.fg, theme.bg,
                0, false, null, null, null, { _, _ -> false }, { 0.342f + it / 400000f }, { _, _ -> null },
                { _, _, _ -> }, {}, {}, {})
        }
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable private fun TabletShelf() {
        Row {
            AppRail(0, {}, {}, {})
            SharedTransitionLayout(Modifier.weight(1f)) {
                AnimatedVisibility(true) {
                    BookshelfContent(books, true, ShelfSort.RECENT_READ, false, remember { SnackbarHostState() },
                        this@SharedTransitionLayout, this, {}, {}, {}, {}, {}, {},
                        groups = listOf("在读", "想读"), twoPane = true, initialDetail = 1)
                }
            }
        }
    }
    @Test fun shelfLight() = shot("31_tablet_shelf", false) { TabletShelf() }
    @Test fun shelfDark() = shot("31_tablet_shelf", true) { TabletShelf() }

    @Test fun comicSpread() = shot("32_tablet_comic_spread", true) {
        PageViewerContent("进击的巨人 第 1 卷", 192, 22, { i, w -> fakePage(i, w.coerceAtMost(900), true) }, setOf(22), {}, {}, {}, comic = true, initialMenu = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PageViewerScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent { NovelTheme(darkTheme = dark, dynamicColor = false, settings = ReaderSettings()) { Surface { content() } } }
        repeat(6) { Thread.sleep(250); rule.waitForIdle(); rule.mainClock.advanceTimeBy(400) }
        rule.onRoot().captureRoboImage("$OUT/${name}_${if (dark) "dark" else "light"}.png")
    }
    @Test fun pdfLight() = shot("33_pdf_viewer", false) {
        PageViewerContent("深度学习", 800, 54, { i, w -> fakePage(i, w.coerceAtMost(1000), false) }, setOf(54), {}, {}, {}, initialMenu = true)
    }
    @Test fun comicDark() = shot("34_comic_viewer", true) {
        PageViewerContent("进击的巨人 第 1 卷", 192, 23, { i, w -> fakePage(i, w.coerceAtMost(1000), true) }, emptySet(), {}, {}, {}, comic = true)
    }
}

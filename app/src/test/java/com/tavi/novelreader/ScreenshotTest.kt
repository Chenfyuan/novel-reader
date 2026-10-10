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
import com.tavi.novelreader.parser.Chapter
import com.tavi.novelreader.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

private val OUT = System.getProperty("shots.dir") ?: "build/screenshots"

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_760_000_000_000L
    private val books = listOf(
        Book(1, "三体", "刘慈欣", "epub", "", null, 36, 12, 0, 0.342f, now - 9_000_000, now - 60_000, readingMillis = 5_400_000),
        Book(2, "活着", "余华", "txt", "", null, 12, 11, 0, 0.98f, now - 8_000_000, now - 3_600_000, readingMillis = 3_000_000),
        Book(3, "红楼梦", "曹雪芹", "txt", "", null, 120, 0, 0, 0f, now - 7_000_000, 0),
        Book(4, "百年孤独", "加西亚·马尔克斯", "epub", "", null, 20, 3, 0, 0.151f, now - 6_000_000, now - 86_400_000, readingMillis = 1_200_000),
        Book(5, "围城", "钱钟书", "txt", "", null, 9, 0, 0, 0f, now - 5_000_000, 0),
        Book(6, "平凡的世界（全三部）", "路遥", "txt", "", null, 162, 40, 0, 0.247f, now - 4_000_000, now - 7_200_000, readingMillis = 2_400_000),
        Book(7, "朝花夕拾", "鲁迅", "epub", "", null, 10, 0, 0, 0f, now - 3_000_000, 0),
    )
    private val chapter = Chapter("第十二章 宇宙闪烁", listOf(
        "汪淼回到家时已是深夜，城市的灯火在窗外静静地铺展开来。他没有开灯，只是站在窗前，看着远处高楼顶端闪烁的航空警示灯。",
        "倒计时仍在继续，那串数字像是直接印在了视网膜上，无论睁眼还是闭眼，都清晰可见。他想起了史强的话：“邪乎到家必有鬼。”",
        "第二天一早，他驱车前往郊外的射电天文观测基地。冬日的阳光很淡，田野里覆着一层薄霜，道路两旁的白杨树光秃秃地伸向天空。",
        "观测基地的负责人是一位年轻的女博士，她听完汪淼的请求后，有些疑惑地看着他，但还是答应帮他查看宇宙微波背景辐射的实时数据。",
        "“如果整个宇宙真的在闪烁，那我们的设备一定能看到。”她说着，把显示器转向了他。",
    ))

    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun shot(name: String, dark: Boolean, seed: Long = 0xFF6750A4L, content: @Composable () -> Unit) {
        rule.setContent {
            NovelTheme(darkTheme = dark, dynamicColor = false, settings = ReaderSettings(seedColor = seed)) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        rule.onRoot().captureRoboImage("$OUT/${name}_${if (dark) "dark" else "light"}.png")
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Composable
    private fun Shelf(grid: Boolean) {
        SharedTransitionLayout {
            AnimatedVisibility(true) {
                BookshelfContent(books, grid, ShelfSort.RECENT_READ, false, remember { SnackbarHostState() },
                    this@SharedTransitionLayout, this, {}, {}, {}, {}, {}, {})
            }
        }
    }

    @Composable
    private fun Page(theme: ReaderTheme, settings: ReaderSettings = ReaderSettings(theme = theme)) {
        Box(Modifier.fillMaxSize().background(theme.bg)) {
            ChapterPager(chapter, 11, 36, PageLayout(settings.fontSize, settings.lineSpacing, settings.paragraphSpacing, settings.indent, settings.pageMargin, showClock = false), settings.effect, theme.fg, theme.bg,
                0, false, null, null, null, { _, _ -> false }, { 0.342f + it / 400000f }, { _, _ -> null },
                { _, _, _ -> }, {}, {}, {})
        }
    }


    @Test fun shelfGridLight() = shot("01_shelf_grid", false) { Shelf(true) }
    @Test fun shelfGridDark() = shot("01_shelf_grid", true) { Shelf(true) }
    @Test fun shelfListLight() = shot("02_shelf_list", false) { Shelf(false) }
    @Test fun shelfListDark() = shot("02_shelf_list", true) { Shelf(false) }
    @Test fun readerLight() = shot("03_reader", false) { Page(ReaderTheme.LIGHT) }
    @Test fun readerDark() = shot("03_reader", true) { Page(ReaderTheme.DARK) }
    @Test fun readerSepia() = shot("03_reader_sepia", false) { Page(ReaderTheme.SEPIA) }

    @Composable private fun Menu(theme: ReaderTheme) {
        Box(Modifier.fillMaxSize()) {
            Page(theme)
            Box(Modifier.align(Alignment.TopCenter)) { ReaderTopBar("三体", "第十二章 宇宙闪烁", true, {}, {}, {}) }
            Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                TtsBar(true, 1.25f, 0L, {}, {}, {}, {}, Modifier.padding(bottom = 16.dp))
                ReaderBottomBar(11, 36, { "第十二章 宇宙闪烁" }, true, theme.dark, {}, {}, {}, {}, {})
            }
        }
    }
    @Test fun menuLight() = shot("04_reader_menu", false) { Menu(ReaderTheme.LIGHT) }
    @Test fun menuDark() = shot("04_reader_menu", true) { Menu(ReaderTheme.DARK) }

    @Composable private fun Sheet(theme: ReaderTheme) {
        Box(Modifier.fillMaxSize()) {
            Page(theme)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = BottomSheetDefaults.ExpandedShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BottomSheetDefaults.DragHandle()
                    Box(Modifier.fillMaxWidth()) { SettingsPanel(ReaderSettings(theme = theme), {}, scrollable = false) }
                }
            }
        }
    }
    @Test fun settingsLight() = shot("05_settings_sheet", false) { Sheet(ReaderTheme.LIGHT) }
    @Test fun settingsDark() = shot("05_settings_sheet", true) { Sheet(ReaderTheme.DARK) }

    private val days = run {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
        listOf(25, 40, 0, 62, 18, 35, 50).mapIndexed { i, m ->
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            ReadingDay(f.format(c.time), m * 60_000L)
        }
    }
    @Test fun statsLight() = shot("06_stats", false) { StatsContent(books, days, {}) }
    @Test fun statsDark() = shot("06_stats", true) { StatsContent(books, days, {}) }

    // ---------- 设置页 ----------
    @Composable private fun Settings(seed: Long = 0xFF6750A4L) {
        SettingsContent(ReaderSettings(dynamicColor = false, seedColor = seed), {}, {}, remember { SnackbarHostState() }, SettingsActions())
    }
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    @Test fun settingsScreenLight() = shot("07_settings", false) { Settings() }
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    @Test fun settingsScreenDark() = shot("07_settings", true) { Settings() }
    @Config(qualifiers = "w411dp-h3200dp-xxhdpi")
    @Test fun settingsScreenSeed() = shot("08_settings_seed_green", false, 0xFF386A20L) { Settings(0xFF386A20L) }
    @Test fun shelfSeed() = shot("09_shelf_seed_green", false, 0xFF386A20L) { Shelf(true) }
    @Test fun shelfSeedDark() = shot("09_shelf_seed_green", true, 0xFF386A20L) { Shelf(true) }
    @Test fun readerParagraphs() = shot("10_reader_spacing", false) { Page(ReaderTheme.SEPIA, ReaderSettings(theme = ReaderTheme.SEPIA, paragraphSpacing = 1f, pageMargin = 32)) }
}

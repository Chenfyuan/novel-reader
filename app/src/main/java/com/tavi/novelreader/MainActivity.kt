package com.tavi.novelreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.tavi.novelreader.ui.BookshelfScreen
import com.tavi.novelreader.ui.Motion
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tavi.novelreader.ui.NovelTheme
import com.tavi.novelreader.ui.ReaderScreen
import com.tavi.novelreader.ui.StatsScreen
import com.tavi.novelreader.ui.SettingsScreen
import com.tavi.novelreader.ui.LicensesScreen
import com.tavi.novelreader.ui.isDark
import com.tavi.novelreader.ui.WindowLayout
import com.tavi.novelreader.ui.LocalWindowLayout
import com.tavi.novelreader.ui.AppRail
import com.tavi.novelreader.ui.SyncScreen
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableSharedFlow

/** 音量键翻页：阅读页开启时拦截音量键，-1 上一页，+1 下一页 */
object VolumeKeys {
    @Volatile var enabled = false
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.state.collectAsStateWithLifecycle()
            val dark = isDark(settings, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            NovelTheme(settings = settings) {
                // 路由：-2 = 统计页，null = 书架，其他 = 阅读页（书籍 id）
                val density = LocalDensity.current
                var route by rememberSaveable { mutableStateOf<Long?>(null) }
                // 自适应：WindowSizeClass + 折叠屏姿态（Jetpack WindowManager）
                val info = androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()
                val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                val wsc = info.windowSizeClass
                val expanded = wsc.isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
                val medium = wsc.isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
                val hinge = info.windowPosture.hingeList.firstOrNull { it.isVertical && it.isSeparating }
                val landscape = cfg.screenWidthDp > cfg.screenHeightDp
                val win = WindowLayout(
                    expanded = expanded, medium = medium,
                    spread = hinge != null || (expanded && landscape),
                    gutter = hinge?.let { with(density) { it.bounds.width.toDp() }.coerceAtLeast(32.dp) } ?: 48.dp,
                    tabletop = info.windowPosture.isTabletop,
                )
                val railSel = when (route) { null -> 0; STATS -> 1; SETTINGS, LICENSES, SYNC -> 2; else -> -1 }
                androidx.compose.runtime.CompositionLocalProvider(LocalWindowLayout provides win) {
                androidx.compose.foundation.layout.Row {
                androidx.compose.animation.AnimatedVisibility(expanded && railSel >= 0) {
                    AppRail(railSel, { route = null }, { route = STATS }, { route = SETTINGS })
                }
                SharedTransitionLayout(androidx.compose.ui.Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            if ((targetState ?: -1L) < 0 && (initialState ?: -1L) < 0) {
                                // M3 共享轴（X）
                                val fwd = depth(targetState) > depth(initialState)
                                val d = with(density) { 30.dp.roundToPx() }
                                (slideInHorizontally(Motion.emphasized(Motion.Medium4)) { if (fwd) d else -d } +
                                    fadeIn(tween(Motion.Medium2, delayMillis = 100, easing = Motion.EmphasizedDecelerate))) togetherWith
                                    (slideOutHorizontally(Motion.emphasized(Motion.Medium4)) { if (fwd) -d else d } +
                                        fadeOut(tween(100, easing = Motion.EmphasizedAccelerate)))
                            } else fadeIn(Motion.standard(Motion.Medium2)) togetherWith fadeOut(Motion.standard(Motion.Medium2))
                        },
                        label = "nav",
                    ) { r ->
                        when (r) {
                            null -> BookshelfScreen(vm, this@SharedTransitionLayout, this@AnimatedContent,
                                onOpen = { route = it }, onStats = { route = STATS }, onSettings = { route = SETTINGS })
                            STATS -> StatsScreen(vm) { route = null }
                            SETTINGS -> SettingsScreen(vm, onBack = { route = null }, onLicenses = { route = LICENSES }, onSync = { route = SYNC })
                            SYNC -> SyncScreen(vm) { route = SETTINGS }
                            LICENSES -> LicensesScreen { route = SETTINGS }
                            else -> ReaderScreen(vm, r, this@SharedTransitionLayout, this@AnimatedContent) { route = null }
                        }
                    }
                }
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.autoSync()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (VolumeKeys.enabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            VolumeKeys.events.tryEmit(if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (VolumeKeys.enabled && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val STATS = -2L; const val SETTINGS = -3L; const val LICENSES = -4L; const val SYNC = -5L
        fun depth(r: Long?) = when (r) { null -> 0; STATS, SETTINGS -> 1; LICENSES, SYNC -> 2; else -> 1 }
    }
}

package com.tavi.novelreader.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Settings

/**
 * 当前窗口布局：由 MainActivity 根据 WindowSizeClass 与折叠屏铰链（Jetpack WindowManager）计算后下发。
 * expanded = 宽度 ≥ 840dp；spread = 横向宽屏或展开的书本姿态，用双页显示；gutter = 两页之间的间隔（有铰链时等于铰链宽度）。
 */
data class WindowLayout(
    val expanded: Boolean = false,
    val medium: Boolean = false,
    val spread: Boolean = false,
    val gutter: Dp = 48.dp,
    val tabletop: Boolean = false,
)

val LocalWindowLayout = compositionLocalOf { WindowLayout() }

/** 宽屏时的侧边导航栏（书架 / 统计 / 设置） */
@androidx.compose.runtime.Composable
fun AppRail(selected: Int, onShelf: () -> Unit, onStats: () -> Unit, onSettings: () -> Unit) {
    androidx.compose.material3.NavigationRail(
        header = {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.MenuBook, "墨阅",
                androidx.compose.ui.Modifier.padding(vertical = 16.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        },
    ) {
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
        val items = listOf(
            Triple("书架", androidx.compose.material.icons.Icons.Rounded.LibraryBooks, onShelf),
            Triple("统计", androidx.compose.material.icons.Icons.Rounded.BarChart, onStats),
            Triple("设置", androidx.compose.material.icons.Icons.Rounded.Settings, onSettings),
        )
        items.forEachIndexed { i, (label, icon, click) ->
            androidx.compose.material3.NavigationRailItem(selected = selected == i, onClick = click,
                icon = { androidx.compose.material3.Icon(icon, null) }, label = { androidx.compose.material3.Text(label) })
        }
    }
}

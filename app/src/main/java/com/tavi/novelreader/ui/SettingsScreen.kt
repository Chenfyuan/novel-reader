package com.tavi.novelreader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tavi.novelreader.AppViewModel
import com.tavi.novelreader.BuildConfig
import com.tavi.novelreader.data.*

/** 设置页所需的外部动作 */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onImportFont: () -> Unit = {},
    val onOpenTtsSettings: () -> Unit = {},
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onLicenses: () -> Unit = {},
)

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onLicenses: () -> Unit) {
    val s by vm.settings.state.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    val ctx = LocalContext.current
    val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFont) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::backup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restore) }
    SettingsContent(s, vm.settings::update, { vm.settings.replace(ReaderSettings()) }, snack, SettingsActions(
        onBack = onBack,
        onImportFont = { font.launch(arrayOf("font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) },
        onOpenTtsSettings = {
            try { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: ActivityNotFoundException) { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        },
        onBackup = { backup.launch("墨阅备份_${java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA).format(java.util.Date())}.json") },
        onRestore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        onClearCache = vm::clearCache,
        onLicenses = onLicenses,
    ))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    s: ReaderSettings,
    update: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onReset: () -> Unit,
    snack: SnackbarHostState,
    actions: SettingsActions,
) {
    BackHandler(onBack = actions.onBack)
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var confirmReset by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(title = { Text("设置") }, scrollBehavior = scroll,
                navigationIcon = { IconButton(actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp)) {
            // ---------- 外观 ----------
            item { Header("外观", first = true) }
            item {
                SwitchItem("动态取色", if (dynamicColorSupported) "使用壁纸颜色生成主题" else "需要 Android 12 及以上版本",
                    s.dynamicColor && dynamicColorSupported, enabled = dynamicColorSupported) { v -> update { it.copy(dynamicColor = v) } }
            }
            item {
                AnimatedVisibility(!(s.dynamicColor && dynamicColorSupported), enter = expandVertically(Motion.emphasized(Motion.Medium4)) + fadeIn(),
                    exit = shrinkVertically(Motion.emphasized(Motion.Medium2)) + fadeOut()) {
                    Column {
                        ListItem(headlineContent = { Text("主题色") },
                            supportingContent = { Text(SeedColors.firstOrNull { it.first == s.seedColor }?.second ?: "自定义") })
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), maxItemsInEachRow = 5) {
                            SeedColors.forEach { (c, name) -> Swatch(Color(c), name, c == s.seedColor) { update { it.copy(seedColor = c) } } }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item {
                ListItem(headlineContent = { Text("深色模式") })
                Segmented(DarkMode.entries, s.darkMode, { it.label }) { v -> update { it.copy(darkMode = v) } }
            }
            item {
                SwitchItem("纯黑模式", "深色模式下使用纯黑背景，适合 OLED 屏幕", s.amoled) { v -> update { it.copy(amoled = v) } }
            }
            item {
                val enabled = !(s.dynamicColor && dynamicColorSupported)
                ListItem(headlineContent = { Text("对比度") },
                    supportingContent = { Text(if (enabled) "提高文字与背景的对比" else "使用动态取色时跟随系统设置") })
                Segmented(ContrastLevel.entries, s.contrast, { it.label }, enabled) { v -> update { it.copy(contrast = v) } }
            }

            // ---------- 阅读 ----------
            item { Header("阅读") }
            item { SliderItem("字号", "${s.fontSize.toInt()}", s.fontSize, 14f..32f) { v -> update { it.copy(fontSize = Math.round(v).toFloat()) } } }
            item { SliderItem("行距", "%.1f".format(s.lineSpacing), s.lineSpacing, 1.2f..2.4f) { v -> update { it.copy(lineSpacing = Math.round(v * 10) / 10f) } } }
            item { SliderItem("段间距", "%.1f".format(s.paragraphSpacing), s.paragraphSpacing, 0f..2f) { v -> update { it.copy(paragraphSpacing = Math.round(v * 10) / 10f) } } }
            item { SliderItem("页边距", "${s.pageMargin} dp", s.pageMargin.toFloat(), 8f..48f) { v -> update { it.copy(pageMargin = (Math.round(v / 4f) * 4)) } } }
            item { SwitchItem("首行缩进", "段落开头空两格", s.indent) { v -> update { it.copy(indent = v) } } }
            item { ChoiceItem("默认阅读主题", ReaderTheme.entries, s.theme, { it.label }) { v -> update { it.copy(theme = v) } } }
            item { ChoiceItem("翻页动画", PageEffect.entries, s.effect, { it.label }) { v -> update { it.copy(effect = v) } } }
            item {
                ChoiceItem("字体", ReaderFont.entries, s.font,
                    { if (it == ReaderFont.CUSTOM) (s.customFontName?.let { n -> "导入字体（$n）" } ?: "导入字体…") else it.label }) { v ->
                    if (v == ReaderFont.CUSTOM && (s.customFontPath == null || v == s.font)) actions.onImportFont()
                    else update { it.copy(font = v) }
                }
            }
            item {
                val ok = android.os.Build.VERSION.SDK_INT >= 29
                SwitchItem("繁体显示", if (ok) "将简体正文转换为繁体显示" else "需要 Android 10 及以上版本",
                    s.traditional && ok, enabled = ok) { v -> update { it.copy(traditional = v) } }
            }
            item { ChoiceItem("点按翻页", TapMode.entries, s.tapMode, { if (it == TapMode.LEFT_RIGHT) "左侧上一页，右侧下一页" else "点按左右两侧都翻到下一页" }) { v -> update { it.copy(tapMode = v) } } }
            item { SwitchItem("音量键翻页", "音量减下一页，音量加上一页", s.volumeKeys) { v -> update { it.copy(volumeKeys = v) } } }
            item { SwitchItem("屏幕常亮", "阅读时不自动锁屏", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } } }
            item { SwitchItem("页脚显示时间和电量", null, s.showClock) { v -> update { it.copy(showClock = v) } } }

            // ---------- 听书 ----------
            item { Header("听书") }
            item { SliderItem("默认语速", "%.2fx".format(s.ttsRate), s.ttsRate, 0.5f..2f) { v -> update { it.copy(ttsRate = Math.round(v * 20) / 20f) } } }
            item { SliderItem("音调", "%.2f".format(s.ttsPitch), s.ttsPitch, 0.5f..2f) { v -> update { it.copy(ttsPitch = Math.round(v * 20) / 20f) } } }
            item {
                ListItem(headlineContent = { Text("语音引擎") }, supportingContent = { Text("在系统设置中选择引擎和语音") },
                    modifier = Modifier.clickable(onClick = actions.onOpenTtsSettings))
            }
            item {
                ChoiceItem("定时关闭", SleepOptions, s.sleepMinutes, ::sleepLabel) { v -> update { it.copy(sleepMinutes = v) } }
            }

            // ---------- 书架 ----------
            item { Header("书架") }
            item { ChoiceItem("默认排序", ShelfSort.entries, s.shelfSort, { it.label }) { v -> update { it.copy(shelfSort = v) } } }
            item {
                ListItem(headlineContent = { Text("默认视图") })
                Segmented(listOf(true, false), s.shelfGrid, { if (it) "网格" else "列表" }) { v -> update { it.copy(shelfGrid = v) } }
            }
            item {
                ListItem(headlineContent = { Text("网格列数") })
                Segmented(listOf(0, 2, 3, 4), s.gridColumns, { if (it == 0) "自动" else "$it 列" }) { v -> update { it.copy(gridColumns = v) } }
            }
            item { SwitchItem("在封面上显示进度", null, s.coverProgress) { v -> update { it.copy(coverProgress = v) } } }

            // ---------- 数据 ----------
            item { Header("数据") }
            item {
                ListItem(headlineContent = { Text("备份到文件") }, supportingContent = { Text("书籍进度、书签、阅读统计和设置（不含书籍文件）") },
                    modifier = Modifier.clickable(onClick = actions.onBackup))
            }
            item {
                ListItem(headlineContent = { Text("从文件恢复") }, supportingContent = { Text("恢复书架上已有书籍的进度与书签") },
                    modifier = Modifier.clickable(onClick = actions.onRestore))
            }
            item { ListItem(headlineContent = { Text("清除缓存") }, modifier = Modifier.clickable(onClick = actions.onClearCache)) }
            item {
                ListItem(headlineContent = { Text("恢复默认设置", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { confirmReset = true })
            }

            // ---------- 关于 ----------
            item { Header("关于") }
            item {
                ListItem(
                    leadingContent = { AppIcon() },
                    headlineContent = { Text("墨阅") },
                    supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") },
                )
            }
            item { ListItem(headlineContent = { Text("开源许可") }, modifier = Modifier.clickable(onClick = actions.onLicenses)) }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("恢复默认设置？") },
        text = { Text("所有外观、阅读、听书和书架设置都将恢复为默认值。书籍、进度和书签不受影响。") },
        confirmButton = { TextButton({ onReset(); confirmReset = false }) { Text("恢复") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("取消") } },
    )
}

val SleepOptions = listOf(0, 15, 30, 45, 60, 90)
fun sleepLabel(m: Int) = if (m == 0) "关闭" else "$m 分钟"

@Composable
fun AppIcon(size: androidx.compose.ui.unit.Dp = 40.dp) {
    Surface(Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun Header(text: String, first: Boolean = false) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (first) 8.dp else 24.dp, bottom = 8.dp))
}

@Composable
private fun SwitchItem(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)) },
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)) } },
        trailingContent = { Switch(checked, null, enabled = enabled) },
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun SliderItem(title: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        ListItem(headlineContent = { Text(title) },
            trailingContent = { Text(value, style = MaterialTheme.typography.labelLarge) },
            modifier = Modifier.height(48.dp))
        Slider(v, onChange, valueRange = range, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, enabled: Boolean = true, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == selected, onClick = { onSelect(o) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
private fun <T> ChoiceItem(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(label(selected)) },
        modifier = Modifier.clickable { open = true })
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                            .selectable(o == selected, role = Role.RadioButton) { onSelect(o); open = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(o == selected, null)
                        Spacer(Modifier.width(16.dp))
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text("取消") } },
    )
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).selectable(selected, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        val ring by androidx.compose.animation.core.animateDpAsState(if (selected) 3.dp else 0.dp, Motion.standard(), label = "ring")
        Box(Modifier.size(48.dp).then(if (ring > 0.5.dp) Modifier.border(ring, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier).padding(ring + 2.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center) {
            AnimatedVisibility(selected, enter = scaleIn(Motion.emphasized(Motion.Medium2)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Icon(Icons.Rounded.Check, name, tint = if (color.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

// ---------------- 开源许可 ----------------
private data class Lib(val name: String, val author: String, val license: String, val text: String)

private const val APACHE = "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied."
private const val MIT = "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software. THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND."

private val LIBS = listOf(
    Lib("Jetpack Compose / Material 3", "The Android Open Source Project", "Apache 2.0", APACHE),
    Lib("AndroidX Activity / Lifecycle / Core", "The Android Open Source Project", "Apache 2.0", APACHE),
    Lib("AndroidX Room", "The Android Open Source Project", "Apache 2.0", APACHE),
    Lib("AndroidX DataStore", "The Android Open Source Project", "Apache 2.0", APACHE),
    Lib("Material Icons", "Google", "Apache 2.0", APACHE),
    Lib("Kotlin / kotlinx.coroutines", "JetBrains", "Apache 2.0", APACHE),
    Lib("MaterialKolor", "Jordon de Hoog", "MIT", MIT),
    Lib("jsoup", "Jonathan Hedley", "MIT", MIT),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("开源许可") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            items(LIBS) { l ->
                var open by remember { mutableStateOf(false) }
                Column(Modifier.animateContentSize(Motion.emphasized(Motion.Medium2))) {
                    ListItem(
                        headlineContent = { Text(l.name) },
                        supportingContent = { Text("${l.author} · ${l.license}") },
                        trailingContent = { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "收起" else "展开") },
                        modifier = Modifier.clickable { open = !open },
                    )
                    if (open) Text(l.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
        }
    }
}

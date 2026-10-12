package com.tavi.novelreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tavi.novelreader.AppViewModel
import com.tavi.novelreader.data.Book
import com.tavi.novelreader.data.ReadingDay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

fun formatDuration(ms: Long): String {
    val m = ms / 60000
    return when {
        m < 1 -> "0 分钟"
        m < 60 -> "$m 分钟"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
}

@Composable
fun StatsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle(emptyList())
    val all by vm.allDays.collectAsStateWithLifecycle(emptyList())
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    StatsContent(books.orEmpty(), days, onBack, goal = {
        GoalCard(all, settings, onChange = { f -> vm.settings.update(f) })
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(list: List<Book>, days: List<ReadingDay>, onBack: () -> Unit, today: Calendar = Calendar.getInstance(),
                 goal: (@Composable () -> Unit)? = null) {
    BackHandler(onBack = onBack)
    val total = list.sumOf { it.readingMillis }
    Scaffold(topBar = {
        TopAppBar(title = { Text("阅读统计") }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } })
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 16.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("累计阅读", formatDuration(total), Modifier.weight(1f).fillMaxHeight())
                    StatCard("藏书", "${list.size} 本", Modifier.weight(1f).fillMaxHeight())
                    StatCard("已读完", "${list.count { it.progress >= 0.99f }} 本", Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (goal != null) item { goal() }
            item {
                OutlinedCard(Modifier.padding(16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("最近 7 天", style = MaterialTheme.typography.titleMedium)
                        Text("每日阅读分钟数", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                        val map = days.associate { it.day to it.millis }
                        val last7 = (6 downTo 0).map { d ->
                            val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -d) }
                            val key = fmt.format(c.time)
                            (if (d == 0) "今天" else key.substring(5).replace('-', '/')) to (map[key] ?: 0L)
                        }
                        val max = last7.maxOf { it.second }.coerceAtLeast(60_000L)
                        Row(Modifier.fillMaxWidth().height(160.dp), verticalAlignment = Alignment.Bottom) {
                            last7.forEach { (label, ms) ->
                                val h by animateFloatAsState(ms.toFloat() / max, Motion.emphasized(), label = "bar")
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${ms / 60000}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(4.dp))
                                    Box(Modifier.width(16.dp).height((112 * h).dp.coerceAtLeast(4.dp))
                                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                                    Spacer(Modifier.height(8.dp))
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("各书阅读时长", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(list.sortedByDescending { it.readingMillis }, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(b.progressLabel()) },
                    trailingContent = { Text(formatDuration(b.readingMillis), style = MaterialTheme.typography.labelLarge) },
                    leadingContent = { BookCover(b, Modifier.width(40.dp).aspectRatio(2f / 3f), compact = true) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

/** 今日目标 + 连续打卡；目标与提醒设置随设置一起同步、备份 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalCard(days: List<ReadingDay>, s: com.tavi.novelreader.data.ReaderSettings,
             onChange: ((com.tavi.novelreader.data.ReaderSettings) -> com.tavi.novelreader.data.ReaderSettings) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = remember(days, s.goalMinutes) { com.tavi.novelreader.data.Goals.streak(days.associate { it.day to it.millis }, s.goalMinutes) }
    var edit by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) onChange { it.copy(goalReminder = true) } }
    fun enableReminder(on: Boolean) {
        if (on && !com.tavi.novelreader.data.Goals.canNotify(ctx) && android.os.Build.VERSION.SDK_INT >= 33)
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else onChange { it.copy(goalReminder = on) }
    }
    Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val p by animateFloatAsState((st.todayMs.toFloat() / st.goalMs).coerceIn(0f, 1f), Motion.emphasized(), label = "goal")
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator({ p }, Modifier.size(72.dp), strokeWidth = 8.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                if (st.doneToday) Icon(Icons.Rounded.Check, "今日已达标", tint = MaterialTheme.colorScheme.primary)
                else Text("${st.todayMs / 60000}", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(if (st.doneToday) "今日目标已完成" else "今日目标 ${s.goalMinutes} 分钟", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("连续达标 ${st.current} 天 · 最长 ${st.best} 天", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            IconButton({ edit = true }) { Icon(Icons.Rounded.Tune, "设置阅读目标", tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
    if (edit) ModalBottomSheet({ edit = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("阅读目标", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("每天 ${s.goalMinutes} 分钟", style = MaterialTheme.typography.titleMedium)
            var v by remember { mutableFloatStateOf(s.goalMinutes.toFloat()) }
            Slider(v, { v = it }, valueRange = 5f..180f, steps = 34, onValueChangeFinished = { onChange { it.copy(goalMinutes = v.toInt()) } })
            Text("当天阅读时长达到目标即自动打卡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            ListItem(headlineContent = { Text("每日提醒") },
                supportingContent = { Text(if (s.goalReminder) "未达标时在 %02d:%02d 提醒".format(s.reminderAt / 60, s.reminderAt % 60) else "关闭") },
                trailingContent = { Switch(s.goalReminder, ::enableReminder) },
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            if (s.goalReminder) TextButton({ pickTime = true }) { Text("更改提醒时间") }
        }
    }
    if (pickTime) {
        val tp = rememberTimePickerState(s.reminderAt / 60, s.reminderAt % 60, is24Hour = true)
        AlertDialog({ pickTime = false }, title = { Text("提醒时间") }, text = { TimePicker(tp) },
            confirmButton = { TextButton({ onChange { it.copy(reminderAt = tp.hour * 60 + tp.minute) }; pickTime = false }) { Text("确定") } },
            dismissButton = { TextButton({ pickTime = false }) { Text("取消") } })
    }
}

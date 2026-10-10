package com.tavi.novelreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
    StatsContent(books.orEmpty(), days, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(list: List<Book>, days: List<ReadingDay>, onBack: () -> Unit, today: Calendar = Calendar.getInstance()) {
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

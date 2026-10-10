package com.tavi.novelreader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tavi.novelreader.data.Highlight

/** 划线颜色（用户内容色，与阅读主题无关） */
val HighlightColors = listOf(
    Color(0xFFFFD54F) to "黄", Color(0xFF81C784) to "绿", Color(0xFF64B5F6) to "蓝",
    Color(0xFFF48FB1) to "粉", Color(0xFFB39DDB) to "紫",
)

fun highlightColor(i: Int) = HighlightColors[i.coerceIn(0, HighlightColors.lastIndex)].first

/** 选中文字后的浮动工具栏 */
@Composable
fun SelectionToolbar(
    onHighlight: (color: Int, style: Int) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyPicking: Boolean = false,
) {
    var picking by remember { mutableStateOf(initiallyPicking) }
    var underline by remember { mutableStateOf(false) }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        AnimatedContent(picking, transitionSpec = { fadeIn(Motion.standard(Motion.Short4)) togetherWith fadeOut(Motion.standard(Motion.Short4)) }, label = "tb") { p ->
            Row(Modifier.padding(horizontal = 8.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!p) {
                    ToolbarAction(Icons.Rounded.BorderColor, "划线") { picking = true }
                    ToolbarAction(Icons.Rounded.EditNote, "笔记", onNote)
                    ToolbarAction(Icons.Rounded.ContentCopy, "复制", onCopy)
                    ToolbarAction(Icons.Rounded.Share, "分享", onShare)
                    ToolbarAction(Icons.Rounded.Search, "搜索", onSearch)
                } else {
                    HighlightColors.forEachIndexed { i, (c, name) ->
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable { onHighlight(i, if (underline) 1 else 0) }
                            .semantics { contentDescription = "${name}色划线" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(c))
                        }
                    }
                    VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp))
                    IconToggleButton(underline, { underline = it }) { Icon(Icons.Rounded.FormatUnderlined, "下划线样式") }
                }
            }
        }
    }
}

@Composable
private fun ToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(56.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 新建 / 编辑划线与笔记 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(h: Highlight, isNew: Boolean, onSave: (Highlight) -> Unit, onDelete: () -> Unit) {
    var color by remember(h.id) { mutableIntStateOf(h.color) }
    var style by remember(h.id) { mutableIntStateOf(h.style) }
    var note by remember(h.id) { mutableStateOf(h.note ?: "") }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text(if (isNew) "添加笔记" else "编辑划线", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(MaterialTheme.shapes.extraSmall).background(highlightColor(color)))
            Spacer(Modifier.width(12.dp))
            Text(h.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        // 48dp 触控区内是 32dp 色块：左移 8dp 让色块与文字对齐同一基准线
        Row(Modifier.offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
            HighlightColors.forEachIndexed { i, (c, name) ->
                Box(Modifier.size(48.dp).clip(CircleShape).selectable(color == i, role = Role.RadioButton) { color = i }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(c)
                        .then(if (color == i) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) {
                        if (color == i) Icon(Icons.Rounded.Check, name, Modifier.size(18.dp), tint = Color.Black)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("背景高亮", "下划线").forEachIndexed { i, l ->
                SegmentedButton(style == i, { style = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("笔记") },
            placeholder = { Text("写下你的想法（可留空）") }, minLines = 3, maxLines = 6)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) TextButton(onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("删除")
            }
            Spacer(Modifier.weight(1f))
            Button({ onSave(h.copy(color = color, style = style, note = note.trim().ifEmpty { null })) }) { Text("保存") }
        }
    }
}

/** 「笔记」标签页 */
@Composable
fun NotesList(
    items: List<Highlight>,
    percentAt: (Int, Int) -> Float,
    onJump: (Highlight) -> Unit,
    onEdit: (Highlight) -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("还没有笔记。在正文中长按文字即可划线或添加笔记。", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${items.size} 条划线 · ${items.count { it.note != null }} 条笔记", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onShare) { Text("分享") }
                TextButton(onExport) { Text("导出 Markdown") }
            }
        }
        items(items, key = { it.id }) { h ->
            ListItem(
                overlineContent = { Text("${h.chapterTitle} · ${"%.1f".format(percentAt(h.chapterIndex, h.start) * 100)}%", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                headlineContent = { Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = h.note?.let { n -> { Text(n, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary) } },
                leadingContent = { Box(Modifier.width(4.dp).height(40.dp).clip(MaterialTheme.shapes.extraSmall).background(highlightColor(h.color))) },
                trailingContent = { IconButton({ onEdit(h) }) { Icon(Icons.Rounded.Edit, "编辑") } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.animateItem().clickable { onJump(h) },
            )
        }
    }
}

/** 笔记导出为 Markdown */
fun notesMarkdown(title: String, author: String, items: List<Highlight>): String = buildString {
    append("# ").append(title).append("\n\n")
    if (author.isNotBlank()) append("作者：").append(author).append("\n\n")
    append("共 ${items.size} 条划线，${items.count { it.note != null }} 条笔记。\n")
    var last: String? = null
    for (h in items) {
        if (h.chapterTitle != last) { append("\n## ").append(h.chapterTitle).append("\n"); last = h.chapterTitle }
        append("\n> ").append(h.text.replace("\n", "\n> ")).append("\n")
        h.note?.let { append("\n").append(it).append("\n") }
    }
}

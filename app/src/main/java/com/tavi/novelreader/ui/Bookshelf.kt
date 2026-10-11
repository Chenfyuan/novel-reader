package com.tavi.novelreader.ui

import androidx.compose.animation.core.animateFloatAsState
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tavi.novelreader.AppViewModel
import com.tavi.novelreader.data.Book
import com.tavi.novelreader.data.ShelfSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BookshelfScreen(
    vm: AppViewModel,
    sts: SharedTransitionScope,
    avs: AnimatedVisibilityScope,
    onOpen: (Long) -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    val importErr by vm.importError.collectAsStateWithLifecycle()
    importErr?.let { e -> ImportErrorDialog(e.summary, e.details) { vm.importError.value = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }
    BookshelfContent(
        books = books, grid = settings.shelfGrid, sort = settings.shelfSort, importing = importing, snack = snack,
        sts = sts, avs = avs,
        onOpen = onOpen, onStats = onStats,
        onImport = { picker.launch(arrayOf("application/epub+zip", "text/plain", "application/pdf", "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/octet-stream")) },
        onToggleGrid = { vm.settings.update { it.copy(shelfGrid = !it.shelfGrid) } },
        onSort = { s -> vm.settings.update { it.copy(shelfSort = s) } },
        onDelete = { vm.delete(it) },
        gridColumns = settings.gridColumns, coverProgress = settings.coverProgress, onSettings = onSettings,
        imports = imports,
        groups = groups.map { it.name },
        twoPane = LocalWindowLayout.current.expanded,
        actions = ShelfActions(
            onMove = { ids, g -> vm.moveBooks(ids, g) },
            onTags = { ids, a, r -> vm.editTags(ids, a, r) },
            onDeleteMany = { vm.deleteBooks(it) },
            onAddGroup = { vm.addGroup(it) },
            onRenameGroup = { a, b -> vm.renameGroup(a, b) },
            onDeleteGroup = { vm.deleteGroup(it) },
            onSync = if (vm.syncPrefs.configured) ({ vm.syncNow() }) else null,
        ),
    )
}

fun Book.progressLabel() = if (lastReadAt == 0L) "未读" else "已读 ${"%.1f".format(progress * 100)}%"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun BookshelfContent(
    books: List<Book>?,
    grid: Boolean,
    sort: ShelfSort,
    importing: Boolean,
    snack: SnackbarHostState,
    sts: SharedTransitionScope,
    avs: AnimatedVisibilityScope,
    onOpen: (Long) -> Unit,
    onStats: () -> Unit,
    onImport: () -> Unit,
    onToggleGrid: () -> Unit,
    onSort: (ShelfSort) -> Unit,
    onDelete: (Book) -> Unit,
    initialQuery: String? = null,
    gridColumns: Int = 0,
    coverProgress: Boolean = true,
    onSettings: () -> Unit = {},
    imports: List<com.tavi.novelreader.AppViewModel.ImportTask> = emptyList(),
    groups: List<String> = emptyList(),
    actions: ShelfActions = ShelfActions(),
    initialSelection: Set<Long> = emptySet(),
    initialFilter: String? = null,
    twoPane: Boolean = false,
    initialDetail: Long? = null,
) {
    var detailId by rememberSaveable { mutableStateOf(initialDetail) }
    val open: (Long) -> Unit = { id -> if (twoPane) detailId = id else onOpen(id) }
    var selected by remember { mutableStateOf(initialSelection) }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var dialog by remember { mutableStateOf<String?>(null) }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    val allTags = remember(books) { books.orEmpty().flatMap { it.tagList() }.distinct().sorted() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var toDelete by remember { mutableStateOf<Book?>(null) }
    var searching by rememberSaveable { mutableStateOf(initialQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    var overflow by remember { mutableStateOf(false) }
    BackHandler(searching) { searching = false; query = "" }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (selected.isNotEmpty()) TopAppBar(
                title = { Text("已选 ${selected.size} 本") },
                navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Rounded.Close, "取消选择") } },
                actions = {
                    IconButton({ selected = books.orEmpty().map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "全选") }
                    IconButton({ dialog = "move" }) { Icon(Icons.Rounded.DriveFileMove, "移到分组") }
                    IconButton({ dialog = "tags" }) { Icon(Icons.Rounded.Sell, "标签") }
                    IconButton({ dialog = "delete" }) { Icon(Icons.Rounded.DeleteOutline, "删除") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) else TopAppBar(
                title = {
                    AnimatedContent(searching, transitionSpec = { fadeIn(Motion.standard(Motion.Short4)) togetherWith fadeOut(Motion.standard(Motion.Short4)) }, label = "t") { s ->
                        if (s) {
                            TextField(
                                query, { query = it }, singleLine = true,
                                placeholder = { Text("搜索书名或作者") },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    focusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                ),
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else Text("书架")
                    }
                },
                navigationIcon = {
                    if (searching) IconButton({ searching = false; query = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "关闭搜索") }
                },
                actions = {
                    if (!searching) IconButton({ searching = true }) { Icon(Icons.Rounded.Search, "搜索") }
                    else if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "清除") }
                    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "设置") }
                    Box {
                        IconButton({ overflow = true }) { Icon(Icons.Rounded.MoreVert, "更多") }
                        DropdownMenu(overflow, { overflow = false }) {
                            Text("排序方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            ShelfSort.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.label) },
                                    leadingIcon = { RadioButton(o == sort, null) },
                                    onClick = { onSort(o); overflow = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (grid) "列表视图" else "网格视图") },
                                leadingIcon = { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, null) },
                                onClick = { overflow = false; onToggleGrid() })
                            DropdownMenuItem(text = { Text("阅读统计") }, leadingIcon = { Icon(Icons.Rounded.BarChart, null) },
                                onClick = { overflow = false; onStats() })
                            DropdownMenuItem(text = { Text("管理分组") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                onClick = { overflow = false; dialog = "groups" })
                            actions.onSync?.let { sync ->
                                DropdownMenuItem(text = { Text("立即同步") }, leadingIcon = { Icon(Icons.Rounded.CloudSync, null) },
                                    onClick = { overflow = false; sync() })
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = {
                    if (importing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Add, null)
                },
                text = { Text(if (importing) "正在导入" else "导入") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val all = books
        val list = remember(all, query, sort, filter) {
            all.orEmpty()
                .filter { query.isBlank() || it.title.contains(query.trim(), true) || it.author.contains(query.trim(), true) }
                .filter { b ->
                    val f = filter
                    when {
                        f == null -> true
                        f == "g:" -> b.groupName.isEmpty()
                        f.startsWith("g:") -> b.groupName == f.removePrefix("g:")
                        f.startsWith("t:") -> f.removePrefix("t:") in b.tagList()
                        else -> true
                    }
                }
                .let { l ->
                    when (sort) {
                        ShelfSort.RECENT_READ -> l.sortedByDescending { maxOf(it.lastReadAt, it.addedAt) }
                        ShelfSort.RECENT_ADDED -> l.sortedByDescending { it.addedAt }
                        ShelfSort.TITLE -> l.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.title })
                    }
                }
        }
        val continueBook = remember(all) { all.orEmpty().filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt } }
        Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
        when {
            all == null -> {}
            all.isEmpty() && imports.isEmpty() -> EmptyShelf(Modifier.padding(pad))
            else -> LazyVerticalGrid(
                columns = if (!grid) GridCells.Fixed(1) else if (gridColumns > 0) GridCells.Fixed(gridColumns) else GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(
                    start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp,
                    top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (grid) 24.dp else 0.dp),
            ) {
                items(imports, key = { "import-${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { t ->
                    ImportCard(t, Modifier.animateItem().padding(horizontal = if (grid) 0.dp else 16.dp))
                }
                if ((groups.isNotEmpty() || allTags.isNotEmpty()) && !searching) {
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        FilterRow(groups, allTags, filter, all.any { it.groupName.isEmpty() }, { filter = it },
                            Modifier.animateItem().padding(horizontal = if (grid) 0.dp else 16.dp))
                    }
                }
                if (continueBook != null && !searching && filter == null) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        ContinueCard(continueBook, Modifier.animateItem().padding(horizontal = if (grid) 0.dp else 16.dp).padding(bottom = 8.dp)) { onOpen(continueBook.id) }
                    }
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Text("全部书籍", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.animateItem().padding(horizontal = if (grid) 0.dp else 16.dp).padding(top = 8.dp, bottom = if (grid) 0.dp else 8.dp))
                    }
                }
                if (list.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("没有找到匹配的书", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp))
                    }
                }
                items(list, key = { it.id }) { book ->
                    val coverMod = with(sts) {
                        Modifier.sharedBounds(
                            rememberSharedContentState("book-${book.id}"), avs,
                            boundsTransform = { _, _ -> Motion.emphasized() },
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        )
                    }
                    if (grid) {
                        Column(
                            Modifier.animateItem()
                                .clip(MaterialTheme.shapes.small)
                                .combinedClickable(onClick = { if (selected.isNotEmpty()) toggle(book.id) else open(book.id) }, onLongClick = { toggle(book.id) })
                        ) {
                            Box {
                                BookCover(book, coverMod.fillMaxWidth().aspectRatio(2f / 3f), showProgress = coverProgress)
                                if (selected.isNotEmpty()) SelectMark(book.id in selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(book.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(book.progressLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    } else {
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { BookCover(book, coverMod.width(48.dp).aspectRatio(2f / 3f), compact = true) },
                            trailingContent = {
                                if (selected.isNotEmpty()) SelectMark(book.id in selected)
                                else Text(book.progressLabel(), style = MaterialTheme.typography.labelMedium)
                            },
                            colors = ListItemDefaults.colors(containerColor = if (book.id in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                            modifier = Modifier.animateItem().combinedClickable(onClick = { if (selected.isNotEmpty()) toggle(book.id) else open(book.id) }, onLongClick = { toggle(book.id) }),
                        )
                    }
                }
            }
        }
        }
        if (twoPane) {
            val d = all.orEmpty().firstOrNull { it.id == detailId } ?: list.firstOrNull()
            Surface(Modifier.width(400.dp).fillMaxHeight().padding(top = pad.calculateTopPadding() + 8.dp, end = 16.dp, bottom = 16.dp),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                AnimatedContent(d, transitionSpec = { fadeIn(Motion.standard(Motion.Medium2)) togetherWith fadeOut(Motion.standard(Motion.Short4)) },
                    contentKey = { it?.id }, label = "detail") { b ->
                    if (b == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("选择一本书查看详情", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else BookDetail(b) { onOpen(b.id) }
                }
            }
        }
        }
    }

    val sel = books.orEmpty().filter { it.id in selected }
    when (dialog) {
        "move" -> MoveDialog(groups, onDismiss = { dialog = null }) { g -> actions.onMove(selected, g); dialog = null; selected = emptySet() }
        "tags" -> TagsDialog(sel, allTags, onDismiss = { dialog = null }) { add, remove -> actions.onTags(selected, add, remove); dialog = null; selected = emptySet() }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("删除 ${selected.size} 本书？") },
            text = { Text("这些书的阅读进度、书签和笔记将一并删除。") },
            confirmButton = { TextButton({ actions.onDeleteMany(selected); dialog = null; selected = emptySet() }) { Text("删除") } },
            dismissButton = { TextButton({ dialog = null }) { Text("取消") } },
        )
        "groups" -> GroupsDialog(groups, actions, onDismiss = { dialog = null })
    }
    toDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("移出书架？") },
            text = { Text("《${b.title}》的阅读进度和书签将一并删除。") },
            confirmButton = { TextButton({ onDelete(b); toDelete = null }) { Text("删除") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("取消") } },
        )
    }
}



@Composable
private fun ContinueCard(book: Book, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.size(width = 64.dp, height = 96.dp), compact = true, color = MaterialTheme.colorScheme.surface)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("继续阅读", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("第 ${book.chapterIndex + 1} / ${book.chapterCount} 章 · ${book.progressLabel()}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = { book.progress }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 封面：有图显示图片；无图使用色调容器 + 书名 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier, compact: Boolean = false, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerHighest, showProgress: Boolean = false) {
    val bmp by produceState<ImageBitmap?>(null, book.coverPath) {
        value = book.coverPath?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Surface(modifier, shape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.small,
        color = color) {
      Box {
        val b = bmp
        if (b != null) {
            Image(b, book.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (compact) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
        } else {
            Column(Modifier.padding(12.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(book.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showProgress && book.lastReadAt > 0) {
            LinearProgressIndicator(progress = { book.progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f), drawStopIndicator = {}, gapSize = 0.dp,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt)
        }
      }
    }
}

@Composable
private fun EmptyShelf(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("书架是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("导入 EPUB 或 TXT 文件开始阅读", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 后台导入中的书：显示解析进度 */
@Composable
fun ImportCard(t: com.tavi.novelreader.AppViewModel.ImportTask, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(t.progress, Motion.standard(), label = "p")
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Downloading, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(if (t.progress < 0.2f) "正在复制文件 · ${(p * 100).toInt()}%" else "正在解析章节 · ${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun com.tavi.novelreader.AppViewModel.ImportTask.title() = name

/** 书架上的批量操作与分组管理 */
class ShelfActions(
    val onMove: (Set<Long>, String) -> Unit = { _, _ -> },
    val onTags: (Set<Long>, Set<String>, Set<String>) -> Unit = { _, _, _ -> },
    val onDeleteMany: (Set<Long>) -> Unit = {},
    val onAddGroup: (String) -> Unit = {},
    val onRenameGroup: (String, String) -> Unit = { _, _ -> },
    val onDeleteGroup: (String) -> Unit = {},
    val onSync: (() -> Unit)? = null,
)

@Composable
private fun SelectMark(on: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.size(24.dp), shape = androidx.compose.foundation.shape.CircleShape, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = if (on) null else androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.outline)) {
        if (on) Icon(Icons.Rounded.Check, "已选", Modifier.padding(3.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(groups: List<String>, tags: List<String>, filter: String?, hasUngrouped: Boolean, onFilter: (String?) -> Unit, modifier: Modifier) {
    androidx.compose.foundation.lazy.LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(filter == null, { onFilter(null) }, label = { Text("全部") }) }
        items(groups.size) { i ->
            val g = groups[i]
            FilterChip(filter == "g:$g", { onFilter(if (filter == "g:$g") null else "g:$g") }, label = { Text(g) },
                leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(FilterChipDefaults.IconSize)) })
        }
        if (groups.isNotEmpty() && hasUngrouped) item {
            FilterChip(filter == "g:", { onFilter(if (filter == "g:") null else "g:") }, label = { Text("未分组") })
        }
        items(tags.size) { i ->
            val t = tags[i]
            FilterChip(filter == "t:$t", { onFilter(if (filter == "t:$t") null else "t:$t") }, label = { Text("#$t") })
        }
    }
}

@Composable
private fun MoveDialog(groups: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DriveFileMove, null) },
        title = { Text("移到分组") },
        text = {
            Column {
                (listOf("") + groups).forEach { g ->
                    ListItem(headlineContent = { Text(g.ifEmpty { "未分组" }) },
                        leadingContent = { Icon(if (g.isEmpty()) Icons.Rounded.FolderOff else Icons.Rounded.Folder, null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clip(MaterialTheme.shapes.small).then(Modifier.clickableNoIndication { onPick(g) }))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("新建分组") },
                    trailingIcon = { IconButton({ onPick(newName.trim()) }, enabled = newName.isNotBlank()) { Icon(Icons.Rounded.Add, "新建并移入") } })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

/** 标签编辑：对所有选中的书，勾选 = 全部添加，取消 = 全部移除 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsDialog(books: List<Book>, allTags: List<String>, onDismiss: () -> Unit, onApply: (Set<String>, Set<String>) -> Unit) {
    val initial = remember(books) { allTags.filter { t -> books.isNotEmpty() && books.all { t in it.tagList() } }.toSet() }
    var on by remember { mutableStateOf(initial) }
    var extra by remember { mutableStateOf(emptyList<String>()) }
    var input by remember { mutableStateOf("") }
    fun addInput() { val t = input.trim().replace(",", ""); if (t.isNotEmpty()) { if (t !in allTags && t !in extra) extra = extra + t; on = on + t }; input = "" }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Sell, null) },
        title = { Text("编辑标签") },
        text = {
            Column {
                Text("为 ${books.size} 本书设置标签", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (allTags + extra).forEach { t ->
                        FilterChip(t in on, { on = if (t in on) on - t else on + t }, label = { Text(t) },
                            leadingIcon = if (t in on) { { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("新标签") },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { addInput() }),
                    trailingIcon = { IconButton(::addInput, enabled = input.isNotBlank()) { Icon(Icons.Rounded.Add, "添加标签") } })
            }
        },
        confirmButton = { TextButton({ addInput(); onApply(on - initial, initial - on) }) { Text("完成") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GroupsDialog(groups: List<String>, actions: ShelfActions, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.FolderOpen, null) },
        title = { Text("管理分组") },
        text = {
            Column {
                if (groups.isEmpty()) Text("还没有分组", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEach { g ->
                    if (editing == g) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                        trailingIcon = { IconButton({ actions.onRenameGroup(g, text); editing = null }) { Icon(Icons.Rounded.Check, "保存") } })
                    else ListItem(
                        headlineContent = { Text(g) },
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        trailingContent = {
                            Row {
                                IconButton({ editing = g; text = g }) { Icon(Icons.Rounded.Edit, "重命名") }
                                IconButton({ actions.onDeleteGroup(g) }) { Icon(Icons.Rounded.DeleteOutline, "删除分组") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("新建分组") },
                    trailingIcon = { IconButton({ actions.onAddGroup(newName); newName = "" }, enabled = newName.isNotBlank()) { Icon(Icons.Rounded.Add, "新建分组") } })
                Text("删除分组不会删除书籍，书会回到「未分组」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("完成") } },
    )
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))

/** 大屏书架右侧的书籍详情 */
@Composable
fun BookDetail(book: Book, onRead: () -> Unit) {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 24.dp)) {
                BookCover(book, Modifier.width(112.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    Text(book.format.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Button(onRead, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (book.lastReadAt > 0) "继续阅读" else "开始阅读")
            }
        }
        if (book.lastReadAt > 0) item {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                LinearProgressIndicator({ book.progress }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(book.progressLabel(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        val paged = book.format == "pdf" || book.format == "cbz"
        val rows = listOfNotNull(
            (if (paged) "页数" else "章节") to (if (paged) "${book.chapterCount} 页" else "${book.chapterCount} 章"),
            "阅读时长" to formatDuration(book.readingMillis),
            book.groupName.takeIf { it.isNotEmpty() }?.let { "分组" to it },
            book.tagList().takeIf { it.isNotEmpty() }?.let { "标签" to it.joinToString("、") },
            "添加时间" to fmt.format(java.util.Date(book.addedAt)),
            if (book.lastReadAt > 0) "上次阅读" to fmt.format(java.util.Date(book.lastReadAt)) else null,
        )
        rows.forEach { (k, v) ->
            item {
                ListItem(headlineContent = { Text(v) }, overlineContent = { Text(k) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}


/** 导入失败：显示原因，可展开查看异常类型与堆栈，便于反馈 */
@Composable
fun ImportErrorDialog(summary: String, details: String, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ErrorOutline, null) },
        title = { Text("无法导入") },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TextButton({ open = !open }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (open) "隐藏详情" else "显示详情")
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (open) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(details, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("确定") } },
        dismissButton = if (open) { { TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(details)) }) { Text("复制") } } } else null,
    )
}

package com.tavi.novelreader.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
    val msg by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }
    BookshelfContent(
        books = books, grid = settings.shelfGrid, sort = settings.shelfSort, importing = importing, snack = snack,
        sts = sts, avs = avs,
        onOpen = onOpen, onStats = onStats,
        onImport = { picker.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) },
        onToggleGrid = { vm.settings.update { it.copy(shelfGrid = !it.shelfGrid) } },
        onSort = { s -> vm.settings.update { it.copy(shelfSort = s) } },
        onDelete = { vm.delete(it) },
        gridColumns = settings.gridColumns, coverProgress = settings.coverProgress, onSettings = onSettings,
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
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    var toDelete by remember { mutableStateOf<Book?>(null) }
    var searching by rememberSaveable { mutableStateOf(initialQuery != null) }
    var query by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    var overflow by remember { mutableStateOf(false) }
    BackHandler(searching) { searching = false; query = "" }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
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
        val list = remember(all, query, sort) {
            all.orEmpty()
                .filter { query.isBlank() || it.title.contains(query.trim(), true) || it.author.contains(query.trim(), true) }
                .let { l ->
                    when (sort) {
                        ShelfSort.RECENT_READ -> l.sortedByDescending { maxOf(it.lastReadAt, it.addedAt) }
                        ShelfSort.RECENT_ADDED -> l.sortedByDescending { it.addedAt }
                        ShelfSort.TITLE -> l.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.CHINA)) { it.title })
                    }
                }
        }
        val continueBook = remember(all) { all.orEmpty().filter { it.lastReadAt > 0 }.maxByOrNull { it.lastReadAt } }
        when {
            all == null -> {}
            all.isEmpty() -> EmptyShelf(Modifier.padding(pad))
            else -> LazyVerticalGrid(
                columns = if (!grid) GridCells.Fixed(1) else if (gridColumns > 0) GridCells.Fixed(gridColumns) else GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(
                    start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp,
                    top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 88.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (grid) 24.dp else 0.dp),
            ) {
                if (continueBook != null && !searching) {
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
                                .combinedClickable(onClick = { onOpen(book.id) }, onLongClick = { toDelete = book })
                        ) {
                            BookCover(book, coverMod.fillMaxWidth().aspectRatio(2f / 3f), showProgress = coverProgress)
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
                            trailingContent = { Text(book.progressLabel(), style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.animateItem().combinedClickable(onClick = { onOpen(book.id) }, onLongClick = { toDelete = book }),
                        )
                    }
                }
            }
        }
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

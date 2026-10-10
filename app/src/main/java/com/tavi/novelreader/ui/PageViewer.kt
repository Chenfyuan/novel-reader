package com.tavi.novelreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tavi.novelreader.AppViewModel
import com.tavi.novelreader.data.Book
import com.tavi.novelreader.data.Bookmark
import com.tavi.novelreader.parser.PageSource
import com.tavi.novelreader.parser.openPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/** PDF / 漫画阅读页：加载书籍与页面源，交给 PageViewerContent 显示 */
@Composable
fun PageBookScreen(vm: AppViewModel, bookId: Long, onBack: () -> Unit) {
    var book by remember { mutableStateOf<Book?>(null) }
    var src by remember { mutableStateOf<PageSource?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        val b = vm.book(bookId) ?: run { error = "找不到这本书"; return@LaunchedEffect }
        book = b
        src = runCatching { withContext(Dispatchers.IO) { openPages(b.format, File(b.filePath)) } }
            .getOrElse { error = "无法打开：${it.message}"; null }
    }
    DisposableEffect(src) { val s = src; onDispose { s?.close() } }
    LaunchedEffect(bookId) { while (true) { delay(15_000); vm.addReadingTime(bookId, 15_000) } }
    val bookmarks by vm.bookmarks(bookId).collectAsStateWithLifecycle(emptyList())
    val b = book; val s = src
    when {
        error != null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Text(error!!, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                FilledTonalButton(onBack) { Text("返回") }
            }
        }
        b == null || s == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        else -> PageViewerContent(
            title = b.title, count = s.count, initialPage = b.chapterIndex.coerceIn(0, (s.count - 1).coerceAtLeast(0)),
            loader = { i, w -> withContext(Dispatchers.IO) { runCatching { s.render(i, w)?.asImageBitmap() }.getOrNull() } },
            bookmarks = bookmarks.map { it.chapterIndex }.toSet(),
            onToggleBookmark = { p ->
                val ex = bookmarks.firstOrNull { it.chapterIndex == p }
                if (ex != null) vm.deleteBookmark(ex.id)
                else vm.addBookmark(Bookmark(bookId = bookId, chapterIndex = p, offset = 0, chapterTitle = "第 ${p + 1} 页", snippet = b.title))
            },
            onPage = { p -> vm.saveProgress(bookId, p, 0, (p + 1f) / s.count) },
            onBack = onBack,
            comic = b.format == "cbz",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewerContent(
    title: String, count: Int, initialPage: Int,
    loader: suspend (Int, Int) -> ImageBitmap?,
    bookmarks: Set<Int>, onToggleBookmark: (Int) -> Unit, onPage: (Int) -> Unit, onBack: () -> Unit,
    comic: Boolean = false, initialMenu: Boolean = false,
) {
    val win = LocalWindowLayout.current
    val per = if (win.spread) 2 else 1
    val views = ceil(count / per.toFloat()).toInt().coerceAtLeast(1)
    val pager = rememberPagerState(initialPage / per) { views }
    var menu by remember { mutableStateOf(initialMenu) }
    var fitWidth by remember { mutableStateOf(!comic) }
    var showMarks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cur = (pager.currentPage * per).coerceAtMost(count - 1)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { onPage((it * per).coerceAtMost(count - 1)) } }
    BackHandler(menu) { menu = false }
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(Modifier.fillMaxSize().background(bg)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, reverseLayout = false) { v ->
            Row(Modifier.fillMaxSize()) {
                for (k in 0 until per) {
                    val i = v * per + k
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (i < count) ZoomablePage(i, loader, fitWidth && per == 1) { menu = !menu }
                    }
                    if (k == 0 && per == 2) Spacer(Modifier.width(win.gutter))
                }
            }
        }
        AnimatedVisibility(menu, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(if (per == 2 && cur + 1 < count) "第 ${cur + 1}–${cur + 2} / $count 页" else "第 ${cur + 1} / $count 页",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    if (per == 1) IconButton({ fitWidth = !fitWidth }) {
                        Icon(if (fitWidth) Icons.Rounded.FitScreen else Icons.Rounded.WidthFull, if (fitWidth) "适应屏幕" else "适应宽度")
                    }
                    IconButton({ showMarks = true }) { Icon(Icons.Rounded.Bookmarks, "书签列表") }
                    IconButton({ onToggleBookmark(cur) }) {
                        Icon(if (cur in bookmarks) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (cur in bookmarks) "删除书签" else "添加书签", tint = if (cur in bookmarks) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        AnimatedVisibility(menu, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${cur + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 32.dp))
                    var drag by remember { mutableStateOf<Float?>(null) }
                    Slider(drag ?: cur.toFloat(), { drag = it }, Modifier.weight(1f).padding(horizontal = 8.dp),
                        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = { drag?.let { d -> scope.launch { pager.scrollToPage(d.toInt() / per) } }; drag = null })
                    Text("$count", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (!menu) Text("${cur + 1} / $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp))
    }
    if (showMarks) ModalBottomSheet({ showMarks = false }) {
        Text("书签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (bookmarks.isEmpty()) Text("还没有书签", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(bookmarks.sorted()) { p ->
                ListItem(headlineContent = { Text("第 ${p + 1} 页") }, leadingContent = { Icon(Icons.Rounded.Bookmark, null) },
                    trailingContent = { IconButton({ onToggleBookmark(p) }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") } },
                    modifier = Modifier.clickable { scope.launch { pager.scrollToPage(p / per) }; showMarks = false; menu = false })
            }
        }
    }
}

/** 可缩放的单页：双指缩放、放大后拖动平移、双击切换 1x / 2.5x；未放大时横向滑动交给翻页 */
@Composable
private fun ZoomablePage(i: Int, loader: suspend (Int, Int) -> ImageBitmap?, fitWidth: Boolean, onTap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val wPx = constraints.maxWidth
        val bmp by produceState<ImageBitmap?>(null, i, wPx) { value = loader(i, (wPx * 1.5f).toInt().coerceAtMost(2400)) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        fun clamp(o: Offset): Offset {
            val mx = w * (scale - 1) / 2; val my = h * (scale - 1) / 2
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        val b = bmp
        if (b == null) { CircularProgressIndicator(); return@BoxWithConstraints }
        val gestures = Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.size > 1 || scale > 1.01f) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                            offset = clamp(offset + ev.calculatePan())
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { p ->
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero }
                    else { scale = 2.5f; offset = clamp((Offset(w / 2, h / 2) - p) * 1.5f) }
                }, onTap = { onTap() })
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        val tall = fitWidth && b.height.toFloat() / b.width > h / w
        if (tall) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(gestures)) {
            Image(b, "第 ${i + 1} 页", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        } else Image(b, "第 ${i + 1} 页", Modifier.fillMaxSize().then(gestures),
            contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit)
    }
}

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
import com.tavi.novelreader.parser.PdfText
import com.tavi.novelreader.parser.PdfTextSource
import com.tavi.novelreader.tts.ReadAloud
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
    var pdfText by remember { mutableStateOf<PdfText?>(null) }
    var hasText by remember { mutableStateOf<Boolean?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(bookId) {
        val b = vm.book(bookId) ?: run { error = "找不到这本书"; return@LaunchedEffect }
        book = b
        src = runCatching { withContext(Dispatchers.IO) { openPages(b.format, File(b.filePath)) } }
            .getOrElse { error = "无法打开：${it.message}"; null }
        // PDF 文字层在后台加载，不影响打开速度；失败时按无文字层处理
        if (b.format == "pdf" && src != null) withContext(Dispatchers.IO) {
            runCatching { PdfText(ctx, File(b.filePath)) }.getOrNull()?.let { t -> pdfText = t; hasText = runCatching { t.hasTextLayer() }.getOrDefault(false) }
                ?: run { hasText = false }
        }
    }
    DisposableEffect(src) { val s = src; onDispose { s?.close() } }
    DisposableEffect(pdfText) { val t = pdfText; onDispose { t?.close() } }
    val tts by ReadAloud.state.collectAsStateWithLifecycle()
    val settings by vm.settings.state.collectAsStateWithLifecycle()
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
            pdf = if (b.format == "pdf") PdfFeatures(
                hasText = hasText,
                pageText = { i -> pdfText?.let { t -> withContext(Dispatchers.IO) { t.page(i) } } ?: "" },
                readingPage = if (tts.active && tts.bookId == bookId) tts.chapter else null,
                onReadAloud = { p -> pdfText?.let { t -> ReadAloud.start(ctx, bookId, b.title, PdfTextSource(t), p, 0, settings.ttsRate, settings.ttsPitch, settings.sleepMinutes) } },
                onStopReading = { ReadAloud.stop() },
            ) else null,
            comicDir = if (b.format == "cbz") vm.comicDir(bookId) else 0,
            globalRtl = vm.settings.state.collectAsStateWithLifecycle().value.comicRtl,
            onComicDir = { vm.setComicDir(bookId, it) },
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
    comicDir: Int = 0, globalRtl: Boolean = false, onComicDir: (Int) -> Unit = {},
    pdf: PdfFeatures? = null,
) {
    var showText by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var lookup by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableIntStateOf(comicDir) }
    // 从右往左：翻页方向、双页内顺序、点按区域都镜像
    val rtl = comic && (if (dir == 0) globalRtl else dir == 2)
    var dirMenu by remember { mutableStateOf(false) }
    val win = LocalWindowLayout.current
    val per = if (win.spread) 2 else 1
    val views = ceil(count / per.toFloat()).toInt().coerceAtLeast(1)
    val pager = rememberPagerState(initialPage / per) { views }
    var menu by remember { mutableStateOf(initialMenu) }
    var fitWidth by remember { mutableStateOf(!comic) }
    var showMarks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    /** 漫画点按区域：左右三分之一翻页（随阅读方向镜像），中间呼出菜单 */
    fun tapAt(x: Float, width: Float) {
        if (!comic || menu) { menu = !menu; return }
        val zone = when { x < width / 3 -> -1; x > width * 2 / 3 -> 1; else -> 0 }
        if (zone == 0) { menu = true; return }
        val forward = if (rtl) zone < 0 else zone > 0
        val target = (pager.currentPage + if (forward) 1 else -1).coerceIn(0, views - 1)
        scope.launch { pager.animateScrollToPage(target) }
    }
    val cur = (pager.currentPage * per).coerceAtMost(count - 1)
    // 朗读 PDF 时跟随朗读页翻页
    val readingPage = pdf?.readingPage
    LaunchedEffect(readingPage) { if (readingPage != null && readingPage / per != pager.currentPage) pager.animateScrollToPage(readingPage / per) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { onPage((it * per).coerceAtMost(count - 1)) } }
    BackHandler(menu) { menu = false }
    val bg = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(Modifier.fillMaxSize().background(bg)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, reverseLayout = rtl) { v ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullW = constraints.maxWidth.toFloat()
            val pageW = fullW / per
            Row(Modifier.fillMaxSize()) {
                for (slot in 0 until per) {
                    val k = if (rtl) per - 1 - slot else slot
                    val i = v * per + k
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (i < count) ZoomablePage(i, loader, fitWidth && per == 1) { x -> tapAt(x + slot * pageW, fullW) }
                    }
                    if (slot == 0 && per == 2) Spacer(Modifier.width(win.gutter))
                }
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
                    if (comic) Box {
                        IconButton({ dirMenu = true }) {
                            Icon(if (rtl) Icons.Rounded.FormatTextdirectionRToL else Icons.Rounded.FormatTextdirectionLToR, "阅读方向")
                        }
                        DropdownMenu(dirMenu, { dirMenu = false }) {
                            listOf(0 to "跟随全局设置（" + (if (globalRtl) "从右往左" else "从左往右") + "）", 1 to "从左往右", 2 to "从右往左").forEach { (d, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { dir = d; onComicDir(d); dirMenu = false },
                                    trailingIcon = { if (dir == d) Icon(Icons.Rounded.Check, null) })
                            }
                        }
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
                Column(Modifier.navigationBarsPadding()) {
                if (pdf != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val ok = pdf.hasText == true
                    PdfAction(Icons.Rounded.TextSnippet, "文字", ok) { showText = true }
                    PdfAction(Icons.Rounded.Search, "搜索", ok) { showSearch = true }
                    if (pdf.readingPage != null) PdfAction(Icons.Rounded.Stop, "停止朗读", true) { pdf.onStopReading() }
                    else PdfAction(Icons.Rounded.Headphones, "朗读", ok) { pdf.onReadAloud(cur); menu = false }
                }
                if (pdf != null && pdf.hasText == false) Text("此 PDF 没有文字层（可能是扫描版），只能按图片阅读", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${cur + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 32.dp))
                    var drag by remember { mutableStateOf<Float?>(null) }
                    // 从右往左时进度条也镜像，拖向左边是往后翻
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    Slider(drag ?: cur.toFloat(), { drag = it }, Modifier.weight(1f).padding(horizontal = 8.dp),
                        valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(),
                        onValueChangeFinished = { drag?.let { d -> scope.launch { pager.scrollToPage(d.toInt() / per) } }; drag = null })
                    }
                    Text("$count", style = MaterialTheme.typography.labelLarge)
                }
                }
            }
        }
        if (!menu) Text("${cur + 1} / $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp))
    }
    if (pdf != null && showText) PdfTextSheet(cur, count, pdf.pageText, onLookup = { lookup = it }) { showText = false }
    if (pdf != null && showSearch) PdfSearchSheet(count, pdf.pageText, onJump = { p -> scope.launch { pager.scrollToPage(p / per) }; showSearch = false; menu = false }) { showSearch = false }
    lookup?.let { t -> LookupSheet(t) { lookup = null } }
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
private fun ZoomablePage(i: Int, loader: suspend (Int, Int) -> ImageBitmap?, fitWidth: Boolean, onTap: (Float) -> Unit) {
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
                }, onTap = { p -> onTap(p.x) })
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        val tall = fitWidth && b.height.toFloat() / b.width > h / w
        if (tall) Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(gestures)) {
            Image(b, "第 ${i + 1} 页", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        } else Image(b, "第 ${i + 1} 页", Modifier.fillMaxSize().then(gestures),
            contentScale = if (fitWidth) ContentScale.FillWidth else ContentScale.Fit)
    }
}

/** PDF 文字层相关功能（选择文字、搜索、朗读）；hasText 为 null 表示仍在检测 */
class PdfFeatures(
    val hasText: Boolean?,
    val pageText: suspend (Int) -> String,
    val readingPage: Int?,
    val onReadAloud: (Int) -> Unit,
    val onStopReading: () -> Unit,
)

@Composable
private fun PdfAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

/** 当前页文字：可自由选择、复制、查词 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfTextSheet(start: Int, count: Int, pageText: suspend (Int) -> String, onLookup: (String) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(start) }
    val text by produceState<String?>(null, page) { value = null; value = pageText(page) }
    var field by remember(text) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text ?: "")) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sel = field.selection
    val chosen = if (sel.collapsed) "" else field.text.substring(sel.min, sel.max)
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ page-- }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, "上一页") }
            Text("第 ${page + 1} / $count 页", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton({ page++ }, enabled = page < count - 1) { Icon(Icons.Rounded.ChevronRight, "下一页") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val t = chosen.ifEmpty { field.text }
            AssistChip({ onLookup(t.take(2000)) }, { Text(if (chosen.isEmpty()) "翻译本页" else "查词 / 翻译") },
                leadingIcon = { Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp)) }, enabled = t.isNotBlank())
            AssistChip({
                ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("墨阅", t))
            }, { Text(if (chosen.isEmpty()) "复制本页" else "复制所选") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) }, enabled = t.isNotBlank())
        }
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(16.dp)) {
            when {
                text == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                text!!.isBlank() -> Text("这一页没有可选择的文字（可能是图片或扫描页）", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                else -> androidx.compose.foundation.text.BasicTextField(field, { field = it }, readOnly = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            }
        }
    }
}

/** 全文搜索：逐页提取文字并匹配，结果显示页码与上下文 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfSearchSheet(count: Int, pageText: suspend (Int) -> String, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var run by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<Pair<Int, String>>() }
    var scanned by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        hits.clear(); scanned = 0
        if (run.isBlank()) return@LaunchedEffect
        for (p in 0 until count) {
            val t = pageText(p)
            var i = t.indexOf(run, ignoreCase = true)
            var n = 0
            while (i >= 0 && n < 20 && hits.size < 500) {
                val a = (i - 20).coerceAtLeast(0); val b = (i + run.length + 30).coerceAtMost(t.length)
                hits += p to t.substring(a, b).replace('\n', ' ')
                i = t.indexOf(run, i + run.length, ignoreCase = true); n++
            }
            scanned = p + 1
            if (hits.size >= 500) break
        }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("在 PDF 中搜索") },
            singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run = q.trim() }))
        if (run.isNotBlank()) {
            if (scanned < count && hits.size < 500) LinearProgressIndicator({ scanned / count.toFloat() }, Modifier.fillMaxWidth().padding(16.dp))
            Text(if (scanned < count && hits.size < 500) "已搜索 $scanned / $count 页，找到 ${hits.size} 处" else "共找到 ${hits.size} 处" + if (hits.size >= 500) "（仅显示前 500 处）" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxHeight(0.75f), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits.size) { k ->
                val (p, snip) = hits[k]
                ListItem(overlineContent = { Text("第 ${p + 1} 页") }, headlineContent = { Text(snip, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onJump(p) })
            }
        }
    }
}

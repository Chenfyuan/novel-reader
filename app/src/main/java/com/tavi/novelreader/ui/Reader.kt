package com.tavi.novelreader.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import android.content.Intent
import com.tavi.novelreader.data.Highlight
import com.tavi.novelreader.parser.*
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.platform.LocalContext
import com.tavi.novelreader.data.ReaderFont
import com.tavi.novelreader.data.TapMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tavi.novelreader.AppViewModel
import com.tavi.novelreader.SearchHit
import com.tavi.novelreader.VolumeKeys
import com.tavi.novelreader.data.Book
import com.tavi.novelreader.data.Bookmark
import com.tavi.novelreader.data.PageEffect
import com.tavi.novelreader.data.ReaderSettings
import com.tavi.novelreader.data.ReaderTheme
import com.tavi.novelreader.parser.*
import com.tavi.novelreader.searchBook
import com.tavi.novelreader.tts.ReadAloud
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

internal data class Seek(val offset: Int, val token: Long = System.nanoTime())

/** 阅读页入口：PDF / 漫画走按页显示，其余走文本阅读器 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ReaderScreen(vm: AppViewModel, bookId: Long, sts: SharedTransitionScope, avs: AnimatedVisibilityScope, onBack: () -> Unit) {
    val fmt by produceState<String?>(null, bookId) { value = vm.book(bookId)?.format ?: "txt" }
    when (fmt) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        "pdf", "cbz" -> PageBookScreen(vm, bookId, onBack)
        else -> TextReaderScreen(vm, bookId, sts, avs, onBack)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TextReaderScreen(
    vm: AppViewModel,
    bookId: Long,
    sts: SharedTransitionScope,
    avs: AnimatedVisibilityScope,
    onBack: () -> Unit,
) {
    val ctx = LocalView.current.context
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    var book by remember { mutableStateOf<Book?>(null) }
    var baseSource by remember { mutableStateOf<BookSource?>(null) }
    // 繁体显示：OpenCC 词库在后台加载
    val source by produceState<BookSource?>(null, baseSource, settings.traditional, settings.zhVariant) {
        val b = baseSource
        value = if (b != null && settings.traditional) withContext(Dispatchers.IO) {
            runCatching { TraditionalSource(b, OpenCC.get({ ctx.assets.open(it) }, settings.zhVariant)) }.getOrDefault(b)
        } else b
    }
    var lens by remember { mutableStateOf<List<Int>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var chapterIndex by remember { mutableIntStateOf(0) }
    var offset by remember { mutableIntStateOf(0) } // 当前页在章节文本中的起始字符
    var pageEnd by remember { mutableIntStateOf(0) }
    var goLast by remember { mutableStateOf(false) }
    var seek by remember { mutableStateOf<Seek?>(null) }
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf<String?>(null) }
    val bookmarks by remember(bookId) { vm.bookmarks(bookId) }.collectAsStateWithLifecycle(emptyList())
    val highlights by remember(bookId) { vm.highlights(bookId) }.collectAsStateWithLifecycle(emptyList())
    var selection by remember { mutableStateOf<IntRange?>(null) }
    var selChapter by remember { mutableIntStateOf(-1) }
    var selChapterObj by remember { mutableStateOf<Chapter?>(null) }
    var scrollSeek by remember { mutableStateOf<ScrollSeek?>(null) }
    var externalLink by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Highlight, Boolean>?>(null) }
    var searchInit by remember { mutableStateOf("") }
    var prepProgress by remember { mutableStateOf<Float?>(null) }
    val tts by ReadAloud.state.collectAsStateWithLifecycle()
    val ttsHere = tts.active && tts.bookId == bookId
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bookId) {
        val b = withContext(Dispatchers.IO) { vm.dao.get(bookId) } ?: return@LaunchedEffect onBack()
        book = b
        chapterIndex = b.chapterIndex.coerceIn(0, (b.chapterCount - 1).coerceAtLeast(0))
        offset = b.chapterOffset
        try {
            val (s, nb) = vm.openBook(b) { p -> prepProgress = p }
            prepProgress = null
            book = nb
            chapterIndex = nb.chapterIndex.coerceIn(0, (nb.chapterCount - 1).coerceAtLeast(0))
            offset = nb.chapterOffset
            lens = nb.lens()
            baseSource = s
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "无法打开此书：${e.message}"
        }
    }
    // 懒加载：只保留当前章节 ±1 的解析结果，并在后台预取相邻章节
    val chapterCache = remember(source) { java.util.concurrent.ConcurrentHashMap<Int, Chapter>() }
    LaunchedEffect(source, chapterIndex) {
        val s = source ?: return@LaunchedEffect
        if (!settings.scrollMode) selection = null
        val idx = chapterIndex
        chapter = chapterCache[idx] ?: runCatching { withContext(Dispatchers.IO) { s.loadChapter(idx) } }
            .getOrElse { textChapter(s.chapterTitles.getOrElse(idx) { "" }, listOf("本章加载失败：${it.message}")) }
            .also { chapterCache[idx] = it }
        chapterCache.keys.filter { it < idx - 1 || it > idx + 1 }.forEach { chapterCache.remove(it) }
        withContext(Dispatchers.IO) {
            listOf(idx + 1, idx - 1).filter { it in s.chapterTitles.indices && !chapterCache.containsKey(it) }.forEach { n ->
                runCatching { s.loadChapter(n) }.getOrNull()?.let { chapterCache[n] = it }
            }
        }
    }

    fun jumpTo(ch: Int, off: Int) {
        if (settings.scrollMode) { scrollSeek = ScrollSeek(ch, off); return }
        if (ch == chapterIndex) seek = Seek(off)
        else { offset = off; goLast = false; chapterIndex = ch }
    }

    // 朗读时跟随当前段落翻页
    LaunchedEffect(ttsHere, tts.chapter, tts.start) {
        if (!ttsHere || settings.scrollMode) return@LaunchedEffect
        if (tts.chapter != chapterIndex) { offset = tts.start; goLast = false; chapterIndex = tts.chapter }
        else if (tts.start < offset || tts.start >= pageEnd) seek = Seek(tts.start)
    }
    LaunchedEffect(settings.ttsPitch) { if (ttsHere) ReadAloud.setPitch(settings.ttsPitch) }
    LaunchedEffect(tts.error) { tts.error?.let { snack.showSnackbar(it); ReadAloud.clearError() } }

    // 精确进度：按全书字符数计算
    fun percentAt(ch: Int, off: Int): Float {
        if (lens.size != (source?.chapterTitles?.size ?: -1) || lens.isEmpty()) {
            val n = (source?.chapterTitles?.size ?: 1).coerceAtLeast(1)
            return ((ch + 0f) / n).coerceIn(0f, 1f)
        }
        val total = lens.sum().coerceAtLeast(1)
        return ((lens.take(ch).sum() + off).toFloat() / total).coerceIn(0f, 1f)
    }

    // 阅读计时（仅在前台时累计）
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // 退出阅读时自动同步
    DisposableEffect(bookId) { onDispose { vm.autoSync() } }
    DisposableEffect(bookId) {
        var last = System.currentTimeMillis()
        val job = scope.launch {
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || ReadAloud.state.value.playing) vm.addReadingTime(bookId, now - last)
                last = now
            }
        }
        onDispose {
            job.cancel()
            vm.addReadingTime(bookId, (System.currentTimeMillis() - last).coerceAtMost(15_000))
        }
    }

    // 沉浸式全屏、亮度、常亮、音量键
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(menu, settings.theme) {
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.isAppearanceLightStatusBars = !settings.theme.dark
        c?.isAppearanceLightNavigationBars = !settings.theme.dark
        if (menu) c?.show(WindowInsetsCompat.Type.systemBars()) else c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(settings.brightness) {
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = if (settings.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else settings.brightness.coerceIn(0.01f, 1f) } }
        onDispose { }
    }
    DisposableEffect(settings.keepScreenOn) { view.keepScreenOn = settings.keepScreenOn; onDispose { } }
    DisposableEffect(settings.volumeKeys) { VolumeKeys.enabled = settings.volumeKeys; onDispose { } }
    DisposableEffect(Unit) {
        onDispose {
            VolumeKeys.enabled = false
            view.keepScreenOn = false
            window?.let {
                it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
                val c = WindowCompat.getInsetsController(it, view)
                c.show(WindowInsetsCompat.Type.systemBars())
                c.isAppearanceLightStatusBars = (view.resources.configuration.uiMode and 0x30) != 0x20
                c.isAppearanceLightNavigationBars = c.isAppearanceLightStatusBars
            }
        }
    }

    // 预测性返回：随手势缩放并圆角化页面
    var backProgress by remember { mutableFloatStateOf(0f) }
    BackHandler(enabled = menu) { menu = false }
    PredictiveBackHandler(enabled = !menu) { events ->
        try {
            events.collect { backProgress = it.progress }
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    val bp by animateFloatAsState(backProgress, spring(stiffness = Spring.StiffnessMediumLow), label = "bp")

    val bg by animateColorAsState(settings.theme.bg, tween(500), label = "bg")
    val fg by animateColorAsState(settings.theme.fg, tween(500), label = "fg")

    // 图片缓存
    val imageCache = remember { LruCache<String, ImageBitmap>(24) }
    val density = LocalDensity.current
    val loadImage: suspend (String, Int) -> ImageBitmap? = { path, targetW ->
        imageCache.get(path) ?: withContext(Dispatchers.IO) {
            val bytes = source?.image(path) ?: return@withContext null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }?.also { imageCache.put(path, it) }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startTts() {
        val s = source ?: return
        val b = book ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        ReadAloud.start(ctx, b.id, b.title, s, chapterIndex, offset, settings.ttsRate, settings.ttsPitch, settings.sleepMinutes)
    }

    fun selectedText(): String {
        val c = selChapterObj ?: return ""
        val r = selection ?: return ""
        val t = c.plainText()
        return t.substring(r.first.coerceIn(0, t.length), (r.last + 1).coerceIn(0, t.length))
            .replace(OBJ.toString(), "").replace("\u3000", "").replace("\u2002", " ").trim()
    }
    fun newHighlight(color: Int, style: Int): Highlight? {
        val r = selection ?: return null
        val c = selChapterObj ?: return null
        return Highlight(bookId = bookId, chapterIndex = selChapter, start = r.first, end = r.last + 1,
            color = color, style = style, text = selectedText(), chapterTitle = c.title)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.exportNotes(uri, notesMarkdown(book?.title ?: "", book?.author ?: "", highlights))
    }

    // 链接：内部链接跳到目标章节 / 锚点，外部链接确认后用浏览器打开
    fun openLink(href: String) {
        if (href.startsWith("http", true) || href.startsWith("mailto:", true)) { externalLink = href; return }
        val s = source ?: return
        val path = href.substringBefore('#'); val frag = href.substringAfter('#', "")
        val ch = s.chapterOf(path) ?: run { scope.launch { snack.showSnackbar("找不到链接目标") }; return }
        scope.launch {
            val c = withContext(Dispatchers.IO) { runCatching { s.loadChapter(ch) }.getOrNull() }
            val off = c?.anchors?.get(frag)?.let { bi -> c.blockStarts().getOrNull(bi) } ?: 0
            searchTerm = null
            jumpTo(ch, off)
        }
    }

    val currentBookmark = bookmarks.firstOrNull { it.chapterIndex == chapterIndex && it.offset >= offset && it.offset < pageEnd.coerceAtLeast(offset + 1) }
    fun toggleBookmark() {
        val c = chapter ?: return
        if (currentBookmark != null) { vm.deleteBookmark(currentBookmark.id); scope.launch { snack.showSnackbar("已删除书签") }; return }
        val text = c.plainText()
        val snip = text.substring(offset.coerceIn(0, text.length), (offset + 60).coerceAtMost(text.length))
            .replace('\n', ' ').replace("\u3000", "").replace(OBJ.toString(), "［图］").trim()
        vm.addBookmark(Bookmark(bookId = bookId, chapterIndex = chapterIndex, offset = offset, chapterTitle = c.title, snippet = snip))
        scope.launch { snack.showSnackbar("已添加书签") }
    }

    with(sts) {
        Box(
            Modifier
                .sharedBounds(
                    rememberSharedContentState("book-$bookId"), avs,
                    boundsTransform = { _, _ -> Motion.emphasized() },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.small),
                )
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.1f * bp
                    scaleX = s; scaleY = s
                    shape = RoundedCornerShape((28 * bp).dp)
                    clip = bp > 0f
                }
                .background(bg)
        ) {
            val ch = chapter
            when {
                error != null -> Text(error!!, color = fg, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                prepProgress != null -> Column(Modifier.align(Alignment.Center).padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("正在优化本书以加快打开速度", style = MaterialTheme.typography.bodyMedium, color = fg)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator({ prepProgress ?: 0f }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("仅首次打开旧书时需要", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                }
                ch == null || source == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val total = source!!.chapterTitles.size
                    val ttsRange = if (ttsHere && tts.chapter == chapterIndex && tts.end > tts.start) tts.start until tts.end else null
                    val fontPath = settings.customFontPath
                    val family = remember(settings.font, fontPath) {
                        when (settings.font) {
                            ReaderFont.SYSTEM -> FontFamily.Default
                            ReaderFont.SERIF -> FontFamily.Serif
                            ReaderFont.CUSTOM -> fontPath?.let { p -> runCatching { FontFamily(Font(File(p))) }.getOrNull() } ?: FontFamily.Default
                        }
                    }
                    val layout = PageLayout(settings.fontSize, settings.lineSpacing, settings.paragraphSpacing, settings.indent,
                        settings.pageMargin, family, settings.tapMode, settings.showClock)
                    val toolbar: @Composable () -> Unit = {
                                SelectionToolbar(
                                    onHighlight = { color, style -> newHighlight(color, style)?.let { vm.putHighlight(it) }; selection = null },
                                    onNote = { newHighlight(0, 0)?.let { editing = it to true } },
                                    onCopy = {
                                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("墨阅", selectedText()))
                                        selection = null
                                        if (Build.VERSION.SDK_INT < 33) scope.launch { snack.showSnackbar("已复制") }
                                    },
                                    onShare = {
                                        val t = selectedText()
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, "「$t」\n—— ${book?.title ?: ""} · ${chapter?.title ?: ""}")
                                        ctx.startActivity(Intent.createChooser(send, "分享摘录"))
                                        selection = null
                                    },
                                    onSearch = { searchInit = selectedText().take(30); selection = null; showSearch = true },
                                )
                            
                    }
                    if (settings.scrollMode) {
                        val src = source!!
                        ScrollReader(
                            chapterCount = total,
                            loadChapter = { i -> chapterCache[i] ?: withContext(Dispatchers.IO) {
                                runCatching { src.loadChapter(i) }.getOrElse { e -> textChapter(src.chapterTitles.getOrElse(i) { "" }, listOf("本章加载失败：${e.message}")) }
                            } },
                            startChapter = chapterIndex, startOffset = offset, seek = scrollSeek,
                            layout = layout, fg = fg, bg = bg,
                            ttsChapter = if (ttsHere) tts.chapter else -1,
                            ttsRange = if (ttsHere && tts.end > tts.start) tts.start until tts.end else null,
                            searchTerm = searchTerm,
                            highlightsOf = { c -> highlights.filter { it.chapterIndex == c } },
                            selectionChapter = selChapter, selection = selection,
                            percentAt = ::percentAt, loadImage = loadImage,
                            onPosition = { c, start, end ->
                                if (c != chapterIndex) chapterIndex = c
                                offset = start; pageEnd = end
                                val b = book ?: return@ScrollReader
                                vm.saveProgress(b.id, c, start, percentAt(c, start))
                            },
                            onSelectionChange = { c, obj, r -> selection = r; selChapter = c; selChapterObj = obj },
                            onHighlightTap = { h -> editing = h to false },
                            onLink = ::openLink,
                            onToggleMenu = { menu = !menu; searchTerm = null; selection = null },
                            selectionToolbar = toolbar,
                            titleOf = { src.chapterTitles.getOrElse(it) { "" } },
                            preloaded = chapterCache,
                        )
                    } else Crossfade(
                        targetState = ch to layout,
                        animationSpec = Motion.standard(Motion.Medium2), label = "layout",
                    ) { (c, lay) ->
                        ChapterPager(
                            chapter = c, chapterIndex = chapterIndex, chapterCount = total,
                            layout = lay, effect = settings.effect, fg = fg, bg = bg,
                            startOffset = offset, startAtEnd = goLast, seek = seek,
                            highlight = ttsRange, searchTerm = searchTerm,
                            bookmarked = { s, e -> bookmarks.any { it.chapterIndex == chapterIndex && it.offset in s until e } },
                            percentAt = { off -> percentAt(chapterIndex, off) },
                            loadImage = loadImage,
                            onPageSettled = { start, end, isLast ->
                                offset = start; pageEnd = end; goLast = false
                                val b = book ?: return@ChapterPager
                                val p = if (isLast && chapterIndex == total - 1) 1f else percentAt(chapterIndex, start)
                                vm.saveProgress(b.id, chapterIndex, start, p)
                            },
                            onPrevChapter = { if (chapterIndex > 0) { goLast = true; offset = 0; chapterIndex-- } },
                            onNextChapter = { if (chapterIndex < total - 1) { goLast = false; offset = 0; chapterIndex++ } },
                            onToggleMenu = { menu = !menu; searchTerm = null; selection = null },
                            highlights = highlights.filter { it.chapterIndex == chapterIndex },
                            selection = selection.takeIf { selChapter == chapterIndex },
                            onSelectionChange = { selection = it; selChapter = chapterIndex; selChapterObj = c },
                            onHighlightTap = { h -> editing = h to false },
                            selectionToolbar = toolbar,
                            onLink = ::openLink,
                        )
                    }
                }
            }

            // 顶部菜单
            AnimatedVisibility(
                menu, enter = slideInVertically(Motion.emphasized(Motion.Medium4)) { -it } + fadeIn(Motion.standard()),
                exit = slideOutVertically(tween(Motion.Short4 + 50, easing = Motion.EmphasizedAccelerate)) { -it } + fadeOut(Motion.standard(Motion.Short4)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    title = book?.title ?: "", subtitle = chapter?.title ?: "",
                    bookmarked = currentBookmark != null,
                    onBack = onBack, onSearch = { showSearch = true }, onBookmark = ::toggleBookmark,
                )
            }

            // 底部：朗读控制条 + 菜单
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(ttsHere, enter = fadeIn(Motion.standard()) + slideInVertically(Motion.emphasized(Motion.Medium4)) { it / 2 },
                    exit = fadeOut(Motion.standard(Motion.Short4))) {
                    TtsBar(tts.playing, settings.ttsRate, tts.sleepAt, { ReadAloud.setSleepTimer(it) },
                        onToggle = { ReadAloud.toggle() },
                        onStop = { ReadAloud.stop() },
                        onRate = { r -> vm.settings.update { it.copy(ttsRate = r) }; ReadAloud.setRate(r) },
                        modifier = Modifier.padding(bottom = 16.dp).then(if (menu) Modifier else Modifier.navigationBarsPadding()))
                }
                AnimatedVisibility(menu, enter = slideInVertically(Motion.emphasized(Motion.Medium4)) { it } + fadeIn(Motion.standard()),
                    exit = slideOutVertically(tween(Motion.Short4 + 50, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut(Motion.standard(Motion.Short4))) {
                    val total = source?.chapterTitles?.size ?: 1
                    ReaderBottomBar(
                        chapterIndex = chapterIndex, chapterCount = total,
                        chapterTitleAt = { source?.chapterTitles?.getOrNull(it) ?: "" },
                        ttsActive = ttsHere, darkTheme = settings.theme.dark,
                        onJumpChapter = { jumpTo(it, 0) },
                        onToc = { showToc = true },
                        onTts = { if (ttsHere) ReadAloud.stop() else { startTts(); menu = false } },
                        onToggleDark = { vm.settings.update { it.copy(theme = if (it.theme.dark) ReaderTheme.LIGHT else ReaderTheme.DARK) } },
                        onSettings = { showSettings = true },
                    )
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (menu) 160.dp else 16.dp))
        }
    }

    if (showToc) {
        val titles = source?.chapterTitles.orEmpty()
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            var tab by remember { mutableIntStateOf(0) }
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("目录 ${titles.size}") })
                Tab(tab == 1, { tab = 1 }, text = { Text("书签 ${bookmarks.size}") })
                Tab(tab == 2, { tab = 2 }, text = { Text("笔记 ${highlights.size}") })
            }
            AnimatedContent(tab, transitionSpec = {
                // M3 fade through
                (fadeIn(tween(210, delayMillis = 90, easing = Motion.EmphasizedDecelerate)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f)) togetherWith
                    fadeOut(tween(90, easing = Motion.EmphasizedAccelerate))
            }, label = "tab") { t ->
                if (t == 0) {
                    val ls = rememberLazyListState((chapterIndex - 3).coerceAtLeast(0))
                    LazyColumn(state = ls, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxHeight(0.8f)) {
                        itemsIndexed(titles) { i, title ->
                            val cur = i == chapterIndex
                            ListItem(
                                headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { if (cur) Icon(Icons.Rounded.Check, "当前章节") },
                                colors = ListItemDefaults.colors(
                                    containerColor = if (cur) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    headlineColor = if (cur) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface),
                                modifier = Modifier.clickable { jumpTo(i, 0); showToc = false; menu = false },
                            )
                        }
                    }
                } else if (t == 2) {
                    NotesList(
                        highlights, ::percentAt,
                        onJump = { h -> jumpTo(h.chapterIndex, h.start); showToc = false; menu = false },
                        onEdit = { h -> editing = h to false },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, "《${book?.title}》笔记")
                                .putExtra(Intent.EXTRA_TEXT, notesMarkdown(book?.title ?: "", book?.author ?: "", highlights))
                            ctx.startActivity(Intent.createChooser(send, "分享笔记"))
                        },
                        onExport = { exportLauncher.launch("${book?.title ?: "笔记"}-笔记.md") },
                        modifier = Modifier.fillMaxHeight(0.8f),
                    )
                } else {
                    if (bookmarks.isEmpty()) {
                        Text("还没有书签。在阅读菜单中点按书签图标即可添加。", Modifier.padding(24.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxHeight(0.8f)) {
                        items(bookmarks, key = { it.id }) { bm ->
                            ListItem(
                                headlineContent = { Text(bm.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                overlineContent = { Text("${bm.chapterTitle} · ${"%.1f".format(percentAt(bm.chapterIndex, bm.offset) * 100)}%") },
                                leadingContent = { Icon(Icons.Rounded.BookmarkBorder, null) },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                trailingContent = { IconButton({ vm.deleteBookmark(bm.id) }) { Icon(Icons.Rounded.DeleteOutline, "删除书签") } },
                                modifier = Modifier.animateItem().clickable { jumpTo(bm.chapterIndex, bm.offset); showToc = false; menu = false },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showSearch) {
        ModalBottomSheet(onDismissRequest = { showSearch = false; searchInit = "" }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SearchPanel(source, searchInit) { hit, q ->
                showSearch = false; searchInit = ""; menu = false
                searchTerm = q
                jumpTo(hit.chapter, hit.offset)
            }
        }
    }
    externalLink?.let { url ->
        AlertDialog(
            onDismissRequest = { externalLink = null },
            icon = { Icon(Icons.Rounded.OpenInNew, null) },
            title = { Text("打开外部链接？") },
            text = { Text(url, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton({
                externalLink = null
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    .onFailure { scope.launch { snack.showSnackbar("没有可以打开此链接的应用") } }
            }) { Text("打开") } },
            dismissButton = { TextButton({ externalLink = null }) { Text("取消") } },
        )
    }
    editing?.let { (h, isNew) ->
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            NoteEditor(h, isNew,
                onSave = { vm.putHighlight(it); editing = null; selection = null },
                onDelete = { vm.deleteHighlight(h.id); editing = null })
        }
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsPanel(settings, { f -> vm.settings.update(f) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(title: String, subtitle: String, bookmarked: Boolean, onBack: () -> Unit, onSearch: () -> Unit, onBookmark: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
        actions = {
            IconButton(onSearch) { Icon(Icons.Rounded.Search, "全文搜索") }
            IconToggleButton(bookmarked, { onBookmark() }) {
                Icon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "删除书签" else "添加书签")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
internal fun ReaderBottomBar(
    chapterIndex: Int, chapterCount: Int, chapterTitleAt: (Int) -> String,
    ttsActive: Boolean, darkTheme: Boolean,
    onJumpChapter: (Int) -> Unit, onToc: () -> Unit, onTts: () -> Unit, onToggleDark: () -> Unit, onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(top = 8.dp, bottom = 8.dp)) {
            var slider by remember(chapterIndex) { mutableFloatStateOf(chapterIndex.toFloat()) }
            Text("第 ${slider.toInt() + 1} / $chapterCount 章 · ${chapterTitleAt(slider.toInt())}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ onJumpChapter(chapterIndex - 1) }, enabled = chapterIndex > 0) { Icon(Icons.Rounded.SkipPrevious, "上一章") }
                Slider(
                    value = slider, onValueChange = { slider = it },
                    onValueChangeFinished = { val t = slider.toInt(); if (t != chapterIndex) onJumpChapter(t) },
                    valueRange = 0f..(chapterCount - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
                IconButton({ onJumpChapter(chapterIndex + 1) }, enabled = chapterIndex < chapterCount - 1) { Icon(Icons.Rounded.SkipNext, "下一章") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                MenuAction(Icons.AutoMirrored.Rounded.List, "目录", onToc)
                MenuAction(if (ttsActive) Icons.Rounded.Stop else Icons.Rounded.Headphones, if (ttsActive) "停止" else "听书", onTts)
                MenuAction(if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (darkTheme) "日间" else "夜间", onToggleDark)
                MenuAction(Icons.Rounded.Tune, "设置", onSettings)
            }
        }
    }
}

@Composable
private fun RowScope.MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).heightIn(min = 64.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun TtsBar(playing: Boolean, rate: Float, sleepAt: Long, onSleep: (Int) -> Unit,
                    onToggle: () -> Unit, onStop: () -> Unit, onRate: (Float) -> Unit, modifier: Modifier = Modifier) {
    val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    var sleepMenu by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), sleepAt) { while (sleepAt > 0) { value = System.currentTimeMillis(); delay(1000) } }
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "继续") }
            TextButton({ onRate(rates[(rates.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0) + 1) % rates.size]) }) {
                Text("${rate}x", style = MaterialTheme.typography.labelLarge)
            }
            Box {
                if (sleepAt > 0) {
                    val left = ((sleepAt - now) / 1000).coerceAtLeast(0)
                    TextButton({ sleepMenu = true }) {
                        Icon(Icons.Rounded.Bedtime, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.labelLarge)
                    }
                } else IconButton({ sleepMenu = true }) { Icon(Icons.Rounded.Bedtime, "定时关闭") }
                DropdownMenu(sleepMenu, { sleepMenu = false }) {
                    Text("定时关闭", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    SleepOptions.forEach { m ->
                        DropdownMenuItem(text = { Text(sleepLabel(m)) }, onClick = { onSleep(m); sleepMenu = false })
                    }
                }
            }
            IconButton(onStop) { Icon(Icons.Rounded.Stop, "停止") }
        }
    }
}

@Composable
private fun SearchPanel(source: BookSource?, initial: String = "", onPick: (SearchHit, String) -> Unit) {
    var q by rememberSaveableString()
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun run() {
        val s = source ?: return
        val term = q.trim(); if (term.isEmpty()) return
        job?.cancel(); hits = emptyList(); running = true; done = false
        job = scope.launch { searchBook(s, term) { hits = it }; running = false; done = true }
    }
    LaunchedEffect(initial) { if (initial.isNotBlank()) { q = initial; run() } }
    Column(Modifier.fillMaxHeight(0.9f)) {
        TextField(q, { q = it }, singleLine = true, placeholder = { Text("在全书中搜索") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Rounded.Close, "清除") } },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        Box(Modifier.height(4.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 0.dp))
        }
        if (done || hits.isNotEmpty()) Text(if (hits.size >= 500) "找到 500+ 处结果（只显示前 500 处）" else "找到 ${hits.size} 处结果",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val hl = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(hits) { h ->
                ListItem(
                    overlineContent = { Text(h.chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = {
                        Text(buildAnnotatedString {
                            append(h.snippet)
                            val s = h.matchStart.coerceIn(0, h.snippet.length)
                            addStyle(hl, s, (s + h.matchLen).coerceAtMost(h.snippet.length))
                        }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.padding(horizontal = 8.dp).clickable { onPick(h, q.trim()) },
                )
            }
        }
    }
}

@Composable
private fun rememberSaveableString() = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPanel(s: ReaderSettings, update: ((ReaderSettings) -> ReaderSettings) -> Unit, scrollable: Boolean = true) {
    val sliderMod = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    Column(Modifier.then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(bottom = 24.dp)) {
        SectionHeader("文字")
        ValueRow("字号", "${s.fontSize.toInt()}")
        Slider(s.fontSize, { v -> update { it.copy(fontSize = Math.round(v).toFloat()) } }, valueRange = 14f..32f, modifier = sliderMod)
        ValueRow("行距", "%.1f".format(s.lineSpacing))
        Slider(s.lineSpacing, { v -> update { it.copy(lineSpacing = Math.round(v * 10) / 10f) } }, valueRange = 1.2f..2.4f, modifier = sliderMod)

        SectionHeader("阅读主题")
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderTheme.entries.forEach { t ->
                FilterChip(
                    selected = t == s.theme, onClick = { update { it.copy(theme = t) } },
                    label = { Text(t.label) },
                    leadingIcon = {
                        Box(Modifier.size(FilterChipDefaults.IconSize).clip(CircleShape).background(t.bg)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    },
                )
            }
        }

        SectionHeader("翻页动画")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            PageEffect.entries.forEachIndexed { i, e ->
                SegmentedButton(
                    selected = s.effect == e, onClick = { update { it.copy(effect = e) } },
                    shape = SegmentedButtonDefaults.itemShape(i, PageEffect.entries.size),
                ) { Text(e.label, maxLines = 1) }
            }
        }

        SectionHeader("屏幕")
        SwitchRow("跟随系统亮度", null, s.brightness < 0) { on -> update { it.copy(brightness = if (on) -1f else 0.5f) } }
        Row(sliderMod, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BrightnessLow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(if (s.brightness < 0) 0.5f else s.brightness, { v -> update { it.copy(brightness = v) } },
                valueRange = 0.01f..1f, enabled = s.brightness >= 0, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Icon(Icons.Rounded.BrightnessHigh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("上下滚动阅读", "连续滚动代替左右翻页", s.scrollMode) { on -> update { it.copy(scrollMode = on) } }
        SwitchRow("屏幕常亮", "阅读时不自动锁屏", s.keepScreenOn) { on -> update { it.copy(keepScreenOn = on) } }
        SwitchRow("音量键翻页", "音量减下一页，音量加上一页", s.volumeKeys) { on -> update { it.copy(volumeKeys = on) } }
    }
}

@Composable
private fun SwitchRow(label: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(MaterialTheme.shapes.medium)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange),
    )
}

/** 把章节排版成多页，返回每页在文本中的字符范围 */
private fun paginate(layout: TextLayoutResult, pageHeight: Int): List<IntRange> {
    val pages = mutableListOf<IntRange>()
    var startLine = 0
    var top = 0f
    for (line in 0 until layout.lineCount) {
        if (layout.getLineBottom(line) - top > pageHeight && line > startLine) {
            pages += layout.getLineStart(startLine) until layout.getLineStart(line)
            startLine = line
            top = layout.getLineTop(line)
        }
    }
    pages += layout.getLineStart(startLine) until layout.layoutInput.text.length
    return pages.filter { !it.isEmpty() }.ifEmpty { listOf(0 until layout.layoutInput.text.length) }
}

data class PageLayout(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.7f,
    val paragraphSpacing: Float = 0.5f,
    val indent: Boolean = true,
    val margin: Int = 24,
    val family: FontFamily = FontFamily.Default,
    val tapMode: TapMode = TapMode.LEFT_RIGHT,
    val showClock: Boolean = true,
)

private val NoTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
private val Hidden = SpanStyle(fontSize = 0.01.sp)

/** 按样式位追加行内文本 */
private fun AnnotatedString.Builder.appendStyled(text: String, spans: List<Span>, base: Float) {
    val start = length
    append(text)
    for (sp in spans) {
        val b = sp.style
        val deco = listOfNotNull(
            if (b and (St.UNDERLINE or St.LINK) != 0) TextDecoration.Underline else null,
            if (b and St.STRIKE != 0) TextDecoration.LineThrough else null,
        )
        addStyle(SpanStyle(
            fontWeight = if (b and St.BOLD != 0) FontWeight.Bold else null,
            fontStyle = if (b and St.ITALIC != 0) FontStyle.Italic else null,
            textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
            baselineShift = when { b and St.SUP != 0 -> BaselineShift.Superscript; b and St.SUB != 0 -> BaselineShift.Subscript; else -> null },
            // 注意：用绝对 sp。em 字号与段落缩进同时出现时，Android 上首行会渲染为空白
            fontSize = when { b and (St.SUP or St.SUB) != 0 -> (base * 0.7f).sp; b and St.SMALL != 0 -> (base * 0.8f).sp; else -> TextUnit.Unspecified },
        ), start + sp.start, start + sp.end)
    }
}

private fun alignOf(a: Int, default: TextAlign) = when (a) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> default }

/** 标题字号：与 M3 字阶比例一致（headline / title） */
private fun headingScale(level: Int) = when (level) { 1 -> 1.5f; 2 -> 1.35f; 3 -> 1.2f; 4 -> 1.1f; else -> 1.0f }

/**
 * 与 Chapter.plainText() 字符一一对应的带样式文本。
 * 块之间的 '\n' 被替换为单独成段的零宽字符，用它的行高实现「段间距」；图片、分隔线、表格用内联占位符。
 */
internal fun buildChapterText(c: Chapter, l: PageLayout): AnnotatedString = buildAnnotatedString {
    val lh = (l.fontSize * l.lineSpacing).sp
    val gap = ParagraphStyle(lineHeight = (l.fontSize * l.paragraphSpacing).coerceAtLeast(1f).sp, lineHeightStyle = NoTrim)
    fun separator() = withStyle(gap) { withStyle(SpanStyle(fontSize = 1.sp)) { append('\u200B') } }
    withStyle(ParagraphStyle(textAlign = TextAlign.Start, lineHeight = (l.fontSize * 1.35f * 1.4f).sp, lineHeightStyle = NoTrim)) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (l.fontSize * 1.35f).sp)) { append(c.title) }
    }
    withStyle(ParagraphStyle(lineHeight = (l.fontSize * 1.2f).sp, lineHeightStyle = NoTrim)) { withStyle(SpanStyle(fontSize = 1.sp)) { append('\u200B') } }
    c.blocks.forEachIndexed { i, b ->
        when (b.kind) {
            BlockKind.IMAGE -> withStyle(ParagraphStyle(textAlign = TextAlign.Center, lineHeightStyle = NoTrim)) { appendInlineContent(IMG_PREFIX + b.image, OBJ.toString()) }
            BlockKind.HR -> withStyle(ParagraphStyle(textAlign = TextAlign.Center, lineHeightStyle = NoTrim)) { appendInlineContent("hr", OBJ.toString()) }
            BlockKind.TABLE -> withStyle(ParagraphStyle(textAlign = TextAlign.Start, lineHeightStyle = NoTrim)) { appendInlineContent("table:$i", OBJ.toString()) }
            BlockKind.HEADING -> {
                val sc = headingScale(b.level)
                withStyle(ParagraphStyle(textAlign = alignOf(b.align, TextAlign.Start), lineHeight = (l.fontSize * sc * 1.45f).sp, lineHeightStyle = NoTrim)) {
                    withStyle(Hidden) { append(b.prefix) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (l.fontSize * sc).sp)) { appendStyled(b.text, b.spans, l.fontSize * sc) }
                }
            }
            BlockKind.QUOTE -> {
                // 首行缩进由前缀字符承担（两字符宽 = 缩进量），避免 TextIndent.firstLine 与字号样式同时出现时首行空白
                val ind = 1.2f * b.level * l.fontSize
                withStyle(ParagraphStyle(textAlign = alignOf(b.align, TextAlign.Justify), lineHeight = lh, lineHeightStyle = NoTrim, textIndent = TextIndent(0.sp, ind.sp))) {
                    withStyle(SpanStyle(fontSize = (ind / 2).sp, color = Color.Transparent)) { append(b.prefix) }
                    appendStyled(b.text, b.spans, l.fontSize)
                }
            }
            BlockKind.LIST -> {
                val base = 1.5f * (b.level - 1)
                withStyle(ParagraphStyle(textAlign = alignOf(b.align, TextAlign.Start), lineHeight = lh, lineHeightStyle = NoTrim,
                    textIndent = TextIndent((base * l.fontSize).sp, ((base + 1.5f) * l.fontSize).sp))) {
                    if (b.marker.isNullOrEmpty()) withStyle(Hidden) { append(b.prefix) } else append(b.prefix)
                    appendStyled(b.text, b.spans, l.fontSize)
                }
            }
            BlockKind.PARA -> {
                val ind = 2f * b.level * l.fontSize
                withStyle(ParagraphStyle(textAlign = alignOf(b.align, TextAlign.Justify), lineHeight = lh, lineHeightStyle = NoTrim,
                    textIndent = if (b.level > 0) TextIndent(0.sp, ind.sp) else null)) {
                    when {
                        b.level > 0 && b.align == 0 -> withStyle(SpanStyle(fontSize = (ind / 2).sp, color = Color.Transparent)) { append(b.prefix) }
                        l.indent && !b.noIndent && b.align == 0 -> append(b.prefix)
                        else -> withStyle(Hidden) { append(b.prefix) }
                    }
                    appendStyled(b.text, b.spans, l.fontSize)
                }
            }
        }
        if (i != c.blocks.lastIndex) separator()
    }
    if (c.blocks.isEmpty()) withStyle(ParagraphStyle(lineHeight = lh, lineHeightStyle = NoTrim)) { append(EMPTY_CHAPTER) }
}

/** 简单表格：等宽列网格；列太多放不下时退化为逐行文本 */
@Composable
private fun TableView(b: Block, rowsShown: Int, fg: Color, fontSize: Float, wide: Boolean) {
    val rows = b.rows.orEmpty()
    val line = fg.copy(alpha = 0.25f)
    val ts = TextStyle(color = fg, fontSize = (fontSize * 0.85f).sp)
    Column(Modifier.fillMaxSize().border(1.dp, line, RoundedCornerShape(4.dp))) {
        rows.take(rowsShown).forEachIndexed { ri, row ->
            if (ri > 0) HorizontalDivider(color = line)
            val head = ri == 0 && b.header
            Row(Modifier.weight(1f).fillMaxWidth().then(if (head) Modifier.background(fg.copy(alpha = 0.06f)) else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                if (wide) Text(row.joinToString(" · "), style = ts, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (head) FontWeight.Bold else null, modifier = Modifier.padding(horizontal = 8.dp))
                else row.forEachIndexed { ci, cell ->
                    if (ci > 0) VerticalDivider(color = line)
                    Text(cell, style = ts, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (head) FontWeight.Bold else null,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }
            }
        }
        if (rowsShown < rows.size) {
            HorizontalDivider(color = line)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("…… 共 ${rows.size} 行", style = ts.copy(color = fg.copy(alpha = 0.6f)))
            }
        }
    }
}

/** 从 off 扩展到所在句子 */
private fun sentenceAt(t: String, off: Int, range: IntRange): IntRange? {
    val stops = "。！？!?；;…\u200B\n" + OBJ
    var s = off; var e = off
    while (s > range.first && t[s - 1] !in stops) s--
    while (e <= range.last && t[e] !in stops) e++
    if (e <= range.last && t[e] in "。！？!?；;…") e++
    while (s < e && (t[s] == '\u3000' || t[s] == ' ' || t[s] == '\u2002')) s++
    return if (s < e) s until e else null
}

/** 章节排版所需的内联占位（图片、分隔线、表格） */
@Composable
internal fun rememberInline(chapter: Chapter, wPx: Int, hPx: Int, layout: PageLayout, fg: Color, loadImage: suspend (String, Int) -> ImageBitmap?): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    return remember(chapter, wPx, hPx, layout.fontSize, fg) {
        val m = HashMap<String, InlineTextContent>()
        chapter.images.forEach { (path, wh) ->
            val (w, h) = wh
            val scale = minOf(wPx.toFloat() / w, hPx * 0.85f / h)
            val pw = (w * scale).coerceAtLeast(1f); val ph = (h * scale).coerceAtLeast(1f)
            m[IMG_PREFIX + path] = InlineTextContent(
                Placeholder(with(density) { pw.toSp() }, with(density) { ph.toSp() }, PlaceholderVerticalAlign.TextBottom)
            ) {
                val bmp by produceState<ImageBitmap?>(null, path) { value = loadImage(path, wPx) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) }
                        ?: CircularProgressIndicator(Modifier.size(24.dp), color = fg.copy(alpha = 0.4f), strokeWidth = 2.dp)
                }
            }
        }
        val wSp = with(density) { wPx.toFloat().toSp() }
        m["hr"] = InlineTextContent(Placeholder(wSp, (layout.fontSize * 1.6f).sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                HorizontalDivider(Modifier.fillMaxWidth(0.4f), color = fg.copy(alpha = 0.3f))
            }
        }
        chapter.blocks.forEachIndexed { i, b ->
            if (b.kind != BlockKind.TABLE) return@forEachIndexed
            val rows = b.rows.orEmpty()
            val cols = rows.maxOf { it.size }.coerceAtLeast(1)
            val rowSp = layout.fontSize * 1.9f
            val maxRows = ((with(density) { (hPx * 0.85f).toSp() }.value) / rowSp).toInt().coerceAtLeast(2)
            val shown = if (rows.size <= maxRows) rows.size else maxRows - 1
            val wide = wSp.value / cols < layout.fontSize * 4f
            val total = shown + if (shown < rows.size) 1 else 0
            m["table:$i"] = InlineTextContent(Placeholder(wSp, (total * rowSp).sp, PlaceholderVerticalAlign.TextBottom)) {
                TableView(b, shown, fg, layout.fontSize, wide)
            }
        }
        m
    }
}

/** 在后台线程排版并分页；null 表示仍在计算 */
@Composable
internal fun rememberPages(text: AnnotatedString, inline: Map<String, InlineTextContent>, style: TextStyle, wPx: Int, hPx: Int): List<IntRange>? {
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val layoutDir = LocalLayoutDirection.current
    val st by produceState<List<IntRange>?>(null, text, wPx, hPx, inline, style.fontFamily, style.fontSize, style.lineHeight) {
        value = withContext(Dispatchers.Default) {
            val placeholders = text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length)
                .mapNotNull { a -> inline[a.item]?.let { AnnotatedString.Range(it.placeholder, a.start, a.end) } }
            val measurer = TextMeasurer(fontResolver, density, layoutDir, 0)
            paginate(measurer.measure(text, style.copy(color = Color.Black), constraints = Constraints(maxWidth = wPx), placeholders = placeholders), hPx)
        }
    }
    return st
}

internal fun readerTextStyle(layout: PageLayout, fg: Color) = TextStyle(color = fg, fontSize = layout.fontSize.sp,
    lineHeight = (layout.fontSize * layout.lineSpacing).sp, lineHeightStyle = NoTrim, textAlign = TextAlign.Justify, fontFamily = layout.family)

/**
 * 一页正文：渲染文字、划线、选区、引用竖线，并处理点按 / 长按 / 拖动选择。
 * 选区以章节字符偏移表示，可以跨页：已有选区时在另一页长按，会把选区延伸到长按位置。
 */
@Composable
internal fun PageBody(
    chapter: Chapter, text: AnnotatedString, starts: IntArray, links: List<LinkRange>, r: IntRange,
    style: TextStyle, inline: Map<String, InlineTextContent>, layout: PageLayout, fg: Color,
    hPad: Dp, topPad: Dp, wPx: Int,
    highlight: IntRange?, searchTerm: String?, highlights: List<Highlight>, selection: IntRange?,
    onSelectionChange: (IntRange?) -> Unit, onHighlightTap: (Highlight) -> Unit, onLink: (String) -> Unit,
    onTapZone: (Offset, IntSize) -> Unit,
    selectionToolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hlBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val selColor = MaterialTheme.colorScheme.primary
    val searchBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val hPadPx = with(density) { hPad.toPx() }; val topPadPx = with(density) { topPad.toPx() }
    val curSelection by rememberUpdatedState(selection)
    val curHighlights by rememberUpdatedState(highlights)
    val curLinks by rememberUpdatedState(links)
    fun offsetAt(pos: Offset): Int? {
        val l = textLayout ?: return null
        val p = pos - Offset(hPadPx, topPadPx)
        if (p.y < 0 || p.y > l.size.height) return null
        return r.first + l.getOffsetForPosition(p).coerceIn(0, r.last - r.first)
    }
    var justSelected by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<IntRange?>(null) }
    Box(modifier
        .pointerInput(r, chapter) {
            detectTapGestures { pos ->
                if (justSelected) { justSelected = false; return@detectTapGestures }
                if (curSelection != null) { onSelectionChange(null); return@detectTapGestures }
                val off = offsetAt(pos)
                if (off != null) {
                    curHighlights.lastOrNull { off >= it.start && off < it.end }?.let { onHighlightTap(it); return@detectTapGestures }
                    curLinks.firstOrNull { off >= it.start && off < it.end }?.let { onLink(it.href); return@detectTapGestures }
                }
                onTapZone(pos, size)
            }
        }
        .pointerInput(r, chapter) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val off = offsetAt(pos) ?: return@detectDragGesturesAfterLongPress
                    val sentence = sentenceAt(text.text, off, r) ?: (off..off)
                    val existing = curSelection
                    val s = if (existing != null) minOf(existing.first, sentence.first) until maxOf(existing.last + 1, sentence.last + 1) else sentence
                    justSelected = true; anchor = if (existing != null) existing else sentence; onSelectionChange(s)
                },
                onDrag = { change, _ ->
                    val a = anchor ?: return@detectDragGesturesAfterLongPress
                    val off = offsetAt(change.position) ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    onSelectionChange(minOf(a.first, off) until maxOf(a.last + 1, off + 1))
                },
            )
        }
    ) {
        val pageText = remember(text, r, highlight, searchTerm, hlBg) {
            val base = text.subSequence(r.first, r.last + 1)
            if (highlight == null && searchTerm.isNullOrEmpty()) base
            else AnnotatedString.Builder(base).apply {
                highlight?.let { h ->
                    val s = maxOf(h.first, r.first); val e = minOf(h.last + 1, r.last + 1)
                    if (s < e) addStyle(SpanStyle(background = hlBg), s - r.first, e - r.first)
                }
                if (!searchTerm.isNullOrEmpty()) {
                    var k = base.text.indexOf(searchTerm, ignoreCase = true)
                    while (k >= 0) {
                        addStyle(SpanStyle(background = searchBg), k, k + searchTerm.length)
                        k = base.text.indexOf(searchTerm, k + searchTerm.length, ignoreCase = true)
                    }
                }
            }.toAnnotatedString()
        }
        Text(
            pageText,
            style = style,
            inlineContent = inline,
            onTextLayout = { textLayout = it },
            modifier = Modifier.padding(start = hPad, end = hPad, top = topPad).width(with(density) { wPx.toDp() })
                .drawBehind {
                    val l = textLayout ?: return@drawBehind
                    val n = r.last + 1 - r.first
                    fun local(o: Int) = (o - r.first).coerceIn(0, n)
                    chapter.blocks.forEachIndexed { i, b ->
                        if (b.kind != BlockKind.QUOTE) return@forEachIndexed
                        val s = local(starts[i]); val e = local(starts[i] + b.plain().length)
                        if (e <= s) return@forEachIndexed
                        val top = l.getLineTop(l.getLineForOffset(s)); val bottom = l.getLineBottom(l.getLineForOffset(e - 1))
                        val x = 1.2f * layout.fontSize.sp.toPx() * (b.level - 1)
                        drawRect(fg.copy(alpha = 0.3f), Offset(x, top + 4.dp.toPx()), Size(3.dp.toPx(), bottom - top - 8.dp.toPx()))
                    }
                    for (h in highlights) {
                        val s = local(h.start); val e = local(h.end)
                        if (e <= s) continue
                        val c = highlightColor(h.color)
                        if (h.style == 0) drawPath(l.getPathForRange(s, e), c.copy(alpha = 0.4f))
                        else for (line in l.getLineForOffset(s)..l.getLineForOffset(e - 1)) {
                            val x1 = if (line == l.getLineForOffset(s)) l.getHorizontalPosition(s, true) else l.getLineLeft(line)
                            val x2 = if (line == l.getLineForOffset(e - 1)) l.getBoundingBox(e - 1).right else l.getLineRight(line)
                            val y = l.getLineBaseline(line) + 4.dp.toPx()
                            drawLine(c, Offset(x1, y), Offset(x2, y), strokeWidth = 2.dp.toPx())
                        }
                        if (h.note != null && h.end <= r.last + 1) {
                            val box = l.getBoundingBox(e - 1)
                            drawCircle(selColor, 3.5.dp.toPx(), Offset(box.right + 3.dp.toPx(), box.top + 4.dp.toPx()))
                        }
                    }
                    selection?.let { sel ->
                        val s = local(sel.first); val e = local(sel.last + 1)
                        if (e > s) {
                            drawPath(l.getPathForRange(s, e), selColor.copy(alpha = 0.25f))
                            val rr = 6.dp.toPx()
                            if (sel.first >= r.first) {
                                val a = l.getBoundingBox(s)
                                drawRect(selColor, Offset(a.left - 1.dp.toPx(), a.top), Size(2.dp.toPx(), a.height))
                                drawCircle(selColor, rr, Offset(a.left, a.bottom + rr * 0.6f))
                            }
                            if (sel.last <= r.last) {
                                val b = l.getBoundingBox(e - 1)
                                drawRect(selColor, Offset(b.right - 1.dp.toPx(), b.top), Size(2.dp.toPx(), b.height))
                                drawCircle(selColor, rr, Offset(b.right, b.bottom + rr * 0.6f))
                            }
                        }
                    }
                },
        )
        // 浮动工具栏：显示在选区结束所在的页（跨页选区也一样），放在选区上方，空间不够时放在下方
        val sel = selection
        val l = textLayout
        if (sel != null && l != null && sel.last + 1 > r.first && sel.first <= r.last && (sel.last <= r.last || sel.first >= r.first)) {
            val n = r.last + 1 - r.first
            val top = l.getBoundingBox((maxOf(sel.first, r.first) - r.first).coerceIn(0, n - 1)).top + topPadPx
            val bottom = l.getBoundingBox((minOf(sel.last, r.last) - r.first).coerceIn(0, n - 1)).bottom + topPadPx
            Layout(content = selectionToolbar, modifier = Modifier.matchParentSize()) { ms, cs ->
                val p = ms.firstOrNull()?.measure(cs.copy(minWidth = 0, minHeight = 0))
                layout(cs.maxWidth, cs.maxHeight) {
                    if (p != null) {
                        val gap = 12.dp.roundToPx()
                        val above = top - p.height - gap
                        val y = if (above >= gap) above else (bottom + gap + 8.dp.toPx()).coerceAtMost((cs.maxHeight - p.height - gap).toFloat().coerceAtLeast(0f))
                        p.place((cs.maxWidth - p.width) / 2, y.toInt())
                    }
                }
            }
        }
    }
}

@Composable
private fun PageFooter(title: String, idx: Int, total: Int, pct: Float, layout: PageLayout, fg: Color, hPad: Dp, modifier: Modifier) {
    val ctx = LocalContext.current
    val clock by produceState("" to -1, layout.showClock) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        while (layout.showClock) {
            val b = runCatching {
                ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?.let { i -> i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
            }.getOrNull() ?: -1
            value = fmt.format(java.util.Date()) to b
            delay(30_000)
        }
    }
    val meta = MaterialTheme.typography.labelSmall.copy(color = fg.copy(alpha = 0.6f))
    Row(modifier.fillMaxWidth().padding(horizontal = hPad).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        if (layout.showClock) {
            Text(clock.first, style = meta)
            Spacer(Modifier.width(8.dp))
            if (clock.second >= 0) {
                Icon(batteryIcon(clock.second), "电量", Modifier.size(12.dp), tint = meta.color)
                Text("${clock.second}", style = meta)
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(if (idx >= 0) "${idx + 1}/${total} · ${"%.1f".format(pct * 100)}%" else "${"%.1f".format(pct * 100)}%", style = meta)
    }
}

@Composable
internal fun ChapterPager(
    chapter: Chapter, chapterIndex: Int, chapterCount: Int,
    layout: PageLayout, effect: PageEffect, fg: Color, bg: Color,
    startOffset: Int, startAtEnd: Boolean, seek: Seek?,
    highlight: IntRange?, searchTerm: String?,
    bookmarked: (Int, Int) -> Boolean,
    percentAt: (Int) -> Float,
    loadImage: suspend (String, Int) -> ImageBitmap?,
    onPageSettled: (start: Int, end: Int, isLast: Boolean) -> Unit,
    onPrevChapter: () -> Unit, onNextChapter: () -> Unit, onToggleMenu: () -> Unit,
    highlights: List<Highlight> = emptyList(),
    selection: IntRange? = null,
    onSelectionChange: (IntRange?) -> Unit = {},
    onHighlightTap: (Highlight) -> Unit = {},
    selectionToolbar: @Composable () -> Unit = {},
    onLink: (String) -> Unit = {},
    curlPreview: CurlState? = null,
) {
    val density = LocalDensity.current
    val style = readerTextStyle(layout, fg)
    val text = remember(chapter, layout) { buildChapterText(chapter, layout) }
    val starts = remember(chapter) { chapter.blockStarts() }
    val links = remember(chapter) { chapter.links() }

    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(if (LocalWindowLayout.current.tabletop) 0.5f else 1f).displayCutoutPadding()) {
        val hPad = layout.margin.dp; val topPad = 32.dp; val bottomPad = 40.dp
        // 宽屏横向 / 折叠屏书本姿态：双页并排（仿真翻页时保持单页）
        val win = LocalWindowLayout.current
        val per = if (win.spread && effect != PageEffect.CURL) 2 else 1
        val colW = if (per == 2) (maxWidth - win.gutter) / 2 else maxWidth
        val gutterPx = with(density) { win.gutter.roundToPx() }
        val fullWpx = constraints.maxWidth
        val wPx = with(density) { (colW - hPad * 2).roundToPx() }.coerceAtLeast(1)
        val hPx = with(density) { (maxHeight - topPad - bottomPad).roundToPx() }.coerceAtLeast(1)
        val inline = rememberInline(chapter, wPx, hPx, layout, fg, loadImage)
        val pages = rememberPages(text, inline, style, wPx, hPx)
        if (pages == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = fg.copy(alpha = 0.6f))
            return@BoxWithConstraints
        }
        val hasPrev = chapterIndex > 0
        val hasNext = chapterIndex < chapterCount - 1
        val lead = if (hasPrev) 1 else 0
        val views = (pages.size + per - 1) / per
        val count = views + lead + (if (hasNext) 1 else 0)
        fun pageOf(off: Int) = pages.indexOfLast { it.first <= off }.coerceAtLeast(0)
        fun viewOf(off: Int) = pageOf(off) / per
        val initial = lead + if (startAtEnd) views - 1 else viewOf(startOffset)
        val pager = rememberPagerState(initialPage = initial) { count }
        val scope = rememberCoroutineScope()

        LaunchedEffect(pager, pages) {
            if (effect == PageEffect.CURL) return@LaunchedEffect
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { p ->
                val idx = p - lead
                when {
                    idx < 0 -> onPrevChapter()
                    idx >= views -> onNextChapter()
                    else -> {
                        val lastP = minOf(idx * per + per - 1, pages.lastIndex)
                        onPageSettled(pages[idx * per].first, pages[lastP].last + 1, lastP == pages.lastIndex)
                    }
                }
            }
        }
        LaunchedEffect(seek, pages) {
            val s = seek ?: return@LaunchedEffect
            if (effect == PageEffect.CURL) return@LaunchedEffect
            val target = lead + viewOf(s.offset)
            if (target != pager.currentPage) pager.animateScrollToPage(target)
        }
        LaunchedEffect(pager) {
            if (effect == PageEffect.CURL) return@LaunchedEffect
            VolumeKeys.events.collect { d ->
                val t = (pager.currentPage + d).coerceIn(0, count - 1)
                if (t != pager.currentPage) pager.animateScrollToPage(t)
            }
        }
        val onTapZone: (Offset, IntSize) -> Unit = { pos, size ->
            val w = size.width
            when {
                pos.x < w / 3f && layout.tapMode == TapMode.LEFT_RIGHT ->
                    scope.launch { if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1) }
                pos.x < w / 3f -> scope.launch { if (pager.currentPage < count - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                pos.x > w * 2f / 3f -> scope.launch { if (pager.currentPage < count - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                else -> onToggleMenu()
            }
        }

        if (effect == PageEffect.CURL) {
            // 仿真翻页：自行管理页码，不使用 HorizontalPager 的滑动
            var cur by remember(pages) { mutableIntStateOf(if (startAtEnd) pages.lastIndex else pageOf(startOffset)) }
            val turns = remember { kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 4) }
            LaunchedEffect(cur, pages) { val r = pages[cur.coerceIn(0, pages.lastIndex)]; onPageSettled(r.first, r.last + 1, cur == pages.lastIndex) }
            LaunchedEffect(seek, pages) { seek?.let { cur = pageOf(it.offset) } }
            LaunchedEffect(Unit) { VolumeKeys.events.collect { turns.tryEmit(it) } }
            val curlTap: (Offset, IntSize) -> Unit = { pos, size ->
                val w = size.width
                when {
                    pos.x < w / 3f && layout.tapMode == TapMode.LEFT_RIGHT -> turns.tryEmit(-1)
                    pos.x < w / 3f || pos.x > w * 2f / 3f -> turns.tryEmit(1)
                    else -> onToggleMenu()
                }
            }
            CurlPager(cur, pages.size, { cur = it.coerceIn(0, pages.lastIndex) }, onPrevChapter, onNextChapter, bg, turns, curlPreview) { i ->
                val r = pages.getOrNull(i)
                Box(Modifier.fillMaxSize().background(bg)) {
                    if (r != null) {
                        PageBody(chapter, text, starts, links, r, style, inline, layout, fg, hPad, topPad, wPx,
                            highlight, searchTerm, highlights, selection, onSelectionChange, onHighlightTap, onLink, curlTap,
                            selectionToolbar, Modifier.fillMaxSize())
                        if (bookmarked(r.first, r.last + 1)) Icon(Icons.Rounded.Bookmark, "已加书签", tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = hPad).size(24.dp))
                        val pct = if (i == pages.lastIndex && chapterIndex == chapterCount - 1) 1f else percentAt(r.first)
                        PageFooter(chapter.title, i, pages.size, pct, layout, fg, hPad, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            return@BoxWithConstraints
        }
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val view = page - lead
            Box(Modifier.fillMaxSize().pageEffect(pager, page, effect).background(bg)) {
                if (view in 0 until views) Row(Modifier.fillMaxSize()) {
                    for (k in 0 until per) {
                        val idx = view * per + k
                        val r = pages.getOrNull(idx)
                        Box(Modifier.width(colW).fillMaxHeight()) {
                            if (r != null) {
                                PageBody(chapter, text, starts, links, r, style, inline, layout, fg, hPad, topPad, wPx,
                                    highlight, searchTerm, highlights, selection, onSelectionChange, onHighlightTap, onLink,
                                    { pos, sz -> onTapZone(Offset(pos.x + k * (sz.width + gutterPx), pos.y), IntSize(fullWpx, sz.height)) },
                                    selectionToolbar, Modifier.fillMaxSize())
                                if (bookmarked(r.first, r.last + 1)) Icon(Icons.Rounded.Bookmark, "已加书签", tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(end = hPad).size(24.dp))
                                val pct = if (idx == pages.lastIndex && chapterIndex == chapterCount - 1) 1f else percentAt(r.first)
                                PageFooter(if (per == 2 && k == 1) "" else chapter.title, idx, pages.size, pct, layout, fg, hPad, Modifier.align(Alignment.BottomCenter))
                            }
                        }
                        if (per == 2 && k == 0) Box(Modifier.width(win.gutter).fillMaxHeight()) {
                            VerticalDivider(Modifier.align(Alignment.Center).fillMaxHeight(0.9f), color = fg.copy(alpha = 0.08f))
                        }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                        Spacer(Modifier.height(16.dp))
                        Text(if (view < 0) "上一章" else "下一章", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/** 滚动模式中的跳转请求 */
internal data class ScrollSeek(val chapter: Int, val offset: Int, val token: Long = System.nanoTime())

/**
 * 上下滚动阅读：每章是 LazyColumn 的一项，章内按页高切块（复用分页结果）以便选区、划线、朗读高亮逐块渲染；
 * 只有可见章节会被加载和排版，章节之间无缝衔接。
 */
@Composable
internal fun ScrollReader(
    chapterCount: Int, loadChapter: suspend (Int) -> Chapter,
    startChapter: Int, startOffset: Int, seek: ScrollSeek?,
    layout: PageLayout, fg: Color, bg: Color,
    ttsChapter: Int, ttsRange: IntRange?, searchTerm: String?,
    highlightsOf: (Int) -> List<Highlight>,
    selectionChapter: Int, selection: IntRange?,
    percentAt: (Int, Int) -> Float,
    loadImage: suspend (String, Int) -> ImageBitmap?,
    onPosition: (chapter: Int, start: Int, end: Int) -> Unit,
    onSelectionChange: (Int, Chapter, IntRange?) -> Unit,
    onHighlightTap: (Highlight) -> Unit,
    onLink: (String) -> Unit,
    onToggleMenu: () -> Unit,
    selectionToolbar: @Composable () -> Unit,
    titleOf: (Int) -> String = { "" },
    preloaded: Map<Int, Chapter> = emptyMap(),
) {
    val density = LocalDensity.current
    val style = readerTextStyle(layout, fg)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startChapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)))
    // 每章的块起点（像素）与字符范围，用于计算 / 恢复阅读位置
    val geometry = remember { HashMap<Int, Pair<IntArray, List<IntRange>>>() }
    var pending by remember { mutableStateOf<ScrollSeek?>(ScrollSeek(startChapter, startOffset)) }
    LaunchedEffect(seek) { seek?.let { pending = it; list.scrollToItem(it.chapter.coerceIn(0, chapterCount - 1)) } }
    var current by remember { mutableStateOf(Triple(startChapter, startOffset, startOffset)) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { (i, off) ->
            if (pending != null) return@collect
            val g = geometry[i] ?: return@collect
            val k = g.first.indexOfLast { it <= off }.coerceAtLeast(0)
            val r = g.second.getOrNull(k) ?: return@collect
            current = Triple(i, r.first, r.last + 1)
            onPosition(i, r.first, r.last + 1)
        }
    }
    LaunchedEffect(list) {
        VolumeKeys.events.collect { d -> list.animateScrollBy(d * list.layoutInfo.viewportSize.height * 0.85f) }
    }
    // 朗读时跟随
    LaunchedEffect(ttsChapter, ttsRange) {
        val r = ttsRange ?: return@LaunchedEffect
        val (c, s, e) = current
        if (ttsChapter != c || r.first < s || r.first >= e) { pending = ScrollSeek(ttsChapter, r.first); list.scrollToItem(ttsChapter) }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(if (LocalWindowLayout.current.tabletop) 0.5f else 1f).background(bg).displayCutoutPadding()) {
        val hPad = layout.margin.dp
        val wPx = with(density) { (maxWidth - hPad * 2).roundToPx() }.coerceAtLeast(1)
        val hPx = with(density) { (maxHeight * 0.6f).roundToPx() }.coerceAtLeast(1)
        val viewH = constraints.maxHeight
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp)) {
            items(chapterCount, key = { it }) { ci ->
                val ch by produceState(preloaded[ci], ci) { if (value == null) value = loadChapter(ci) }
                val c = ch
                if (c == null) {
                    Box(Modifier.fillMaxWidth().height(with(density) { viewH.toDp() }), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                    }
                    return@items
                }
                val text = remember(c, layout) { buildChapterText(c, layout) }
                val starts = remember(c) { c.blockStarts() }
                val links = remember(c) { c.links() }
                val inline = rememberInline(c, wPx, (viewH * 0.85f).toInt(), layout, fg, loadImage)
                val pages = rememberPages(text, inline, style, wPx, hPx)
                if (pages == null) {
                    Box(Modifier.fillMaxWidth().height(with(density) { viewH.toDp() }), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                    }
                    return@items
                }
                val tops = remember(pages) { IntArray(pages.size) }
                val hls = highlightsOf(ci)
                Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                    pages.forEachIndexed { pi, r ->
                        PageBody(c, text, starts, links, r, style, inline, layout, fg, hPad, 0.dp, wPx,
                            if (ttsChapter == ci) ttsRange else null, searchTerm, hls,
                            if (selectionChapter == ci) selection else null,
                            { onSelectionChange(ci, c, it) }, onHighlightTap, onLink,
                            { _, _ -> onToggleMenu() },
                            selectionToolbar,
                            Modifier.fillMaxWidth().onGloballyPositioned { tops[pi] = it.positionInParent().y.toInt() })
                    }
                }
                geometry[ci] = tops to pages
                // 恢复 / 跳转到章内位置
                LaunchedEffect(pages, pending) {
                    val p = pending ?: return@LaunchedEffect
                    if (p.chapter != ci) return@LaunchedEffect
                    withFrameNanos { }
                    val k = pages.indexOfLast { it.first <= p.offset }.coerceAtLeast(0)
                    list.scrollToItem(ci, tops[k])
                    pending = null
                    current = Triple(ci, pages[k].first, pages[k].last + 1)
                    onPosition(ci, pages[k].first, pages[k].last + 1)
                }
            }
        }
        val (c, s, _) = current
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(bg)) {
            PageFooter(titleOf(c), -1, 0, percentAt(c, s), layout, fg, hPad, Modifier.padding(top = 8.dp))
        }
    }
}

private fun batteryIcon(level: Int) = when {
    level >= 90 -> Icons.Rounded.BatteryFull
    level >= 50 -> Icons.Rounded.Battery5Bar
    level >= 20 -> Icons.Rounded.Battery3Bar
    else -> Icons.Rounded.Battery1Bar
}

/** 翻页特效：平移 / 3D 立方体 / 层叠覆盖 */
private fun Modifier.pageEffect(pager: PagerState, page: Int, effect: PageEffect): Modifier =
    this
        .zIndex(if (effect == PageEffect.STACK) ((pager.currentPage - page) + pager.currentPageOffsetFraction) else 0f)
        .graphicsLayer {
            val off = (pager.currentPage - page) + pager.currentPageOffsetFraction // >0: 该页正向左离开
            when (effect) {
                PageEffect.SLIDE -> {
                    val f = 1f - off.absoluteValue.coerceIn(0f, 1f)
                    alpha = lerp(0.6f, 1f, f)
                }
                PageEffect.CUBE -> {
                    cameraDistance = 14f * density
                    transformOrigin = TransformOrigin(if (off > 0) 1f else 0f, 0.5f)
                    rotationY = (-off * 75f).coerceIn(-90f, 90f)
                    alpha = if (off.absoluteValue >= 1f) 0f else 1f
                }
                PageEffect.CURL -> {}
                PageEffect.STACK -> {
                    if (off < 0) {
                        translationX = off * size.width
                        val f = (1f + off).coerceIn(0f, 1f)
                        val s = lerp(0.92f, 1f, f)
                        scaleX = s; scaleY = s
                        alpha = lerp(0.5f, 1f, f)
                    } else if (off > 0) {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = 20f * density
                        rotationY = off.coerceIn(0f, 1f) * 25f
                        shadowElevation = 24f * (1f - off.coerceIn(0f, 1f))
                    }
                }
            }
        }

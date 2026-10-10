package com.tavi.novelreader.ui

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

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
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
    val source = remember(baseSource, settings.traditional) {
        val b = baseSource
        if (b != null && settings.traditional && Build.VERSION.SDK_INT >= 29) runCatching { TraditionalSource(b) }.getOrDefault(b) else b
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
            val s = withContext(Dispatchers.IO) { BookLoader.open(b.format, File(b.filePath), b.title) }
            baseSource = s
            lens = vm.ensureLens(b, s)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "无法打开此书：${e.message}"
        }
    }
    LaunchedEffect(source, chapterIndex) {
        val s = source ?: return@LaunchedEffect
        chapter = runCatching { withContext(Dispatchers.IO) { s.loadChapter(chapterIndex) } }
            .getOrElse { Chapter(s.chapterTitles.getOrElse(chapterIndex) { "" }, listOf("本章加载失败：${it.message}")) }
    }

    fun jumpTo(ch: Int, off: Int) {
        if (ch == chapterIndex) seek = Seek(off)
        else { offset = off; goLast = false; chapterIndex = ch }
    }

    // 朗读时跟随当前段落翻页
    LaunchedEffect(ttsHere, tts.chapter, tts.start) {
        if (!ttsHere) return@LaunchedEffect
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
                    Crossfade(
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
                            onToggleMenu = { menu = !menu; searchTerm = null },
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
        ModalBottomSheet(onDismissRequest = { showSearch = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SearchPanel(source) { hit, q ->
                showSearch = false; menu = false
                searchTerm = q
                jumpTo(hit.chapter, hit.offset)
            }
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
private fun SearchPanel(source: BookSource?, onPick: (SearchHit, String) -> Unit) {
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

/**
 * 与 Chapter.plainText() 字符一一对应的带样式文本。
 * 段落之间的 '\n' 被替换为单独成段的零宽字符，用它的行高实现「段间距」；图片用内联占位符。
 */
private fun buildChapterText(c: Chapter, l: PageLayout): AnnotatedString = buildAnnotatedString {
    val body = ParagraphStyle(textAlign = TextAlign.Justify, lineHeight = (l.fontSize * l.lineSpacing).sp, lineHeightStyle = NoTrim)
    val gap = ParagraphStyle(lineHeight = (l.fontSize * l.paragraphSpacing).coerceAtLeast(1f).sp, lineHeightStyle = NoTrim)
    fun separator() = withStyle(gap) { withStyle(SpanStyle(fontSize = 1.sp)) { append('\u200B') } }
    withStyle(ParagraphStyle(textAlign = TextAlign.Start, lineHeight = (l.fontSize * 1.35f * 1.4f).sp, lineHeightStyle = NoTrim)) {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (l.fontSize * 1.35f).sp)) { append(c.title) }
    }
    withStyle(ParagraphStyle(lineHeight = (l.fontSize * 1.2f).sp, lineHeightStyle = NoTrim)) { withStyle(SpanStyle(fontSize = 1.sp)) { append('\u200B') } }
    c.paragraphs.forEachIndexed { i, p ->
        if (p.startsWith(IMG_PREFIX)) withStyle(ParagraphStyle(textAlign = TextAlign.Center, lineHeightStyle = NoTrim)) { appendInlineContent(p, OBJ.toString()) }
        else withStyle(body) {
            if (l.indent) append("\u3000\u3000") else withStyle(SpanStyle(fontSize = 0.01.sp)) { append("\u3000\u3000") }
            append(p)
        }
        if (i != c.paragraphs.lastIndex) separator()
    }
    if (c.paragraphs.isEmpty()) withStyle(body) { append("\u3000\u3000（本章无文字内容）") }
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
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val style = TextStyle(color = fg, fontSize = layout.fontSize.sp, lineHeight = (layout.fontSize * layout.lineSpacing).sp,
        lineHeightStyle = NoTrim, textAlign = TextAlign.Justify, fontFamily = layout.family)
    val text = remember(chapter, layout) { buildChapterText(chapter, layout) }
    val hlBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val searchBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)

    BoxWithConstraints(Modifier.fillMaxSize().displayCutoutPadding()) {
        val hPad = layout.margin.dp; val topPad = 32.dp; val bottomPad = 40.dp
        val wPx = with(density) { (maxWidth - hPad * 2).roundToPx() }.coerceAtLeast(1)
        val hPx = with(density) { (maxHeight - topPad - bottomPad).roundToPx() }.coerceAtLeast(1)

        // 图片占位：适应页宽，且不超过页高的 85%
        val inline: Map<String, InlineTextContent> = remember(chapter, wPx, hPx) {
            chapter.images.mapNotNull { (path, wh) ->
                val (w, h) = wh
                val scale = minOf(wPx.toFloat() / w, hPx * 0.85f / h)
                val pw = (w * scale).coerceAtLeast(1f); val ph = (h * scale).coerceAtLeast(1f)
                val key = IMG_PREFIX + path
                key to InlineTextContent(
                    Placeholder(with(density) { pw.toSp() }, with(density) { ph.toSp() }, PlaceholderVerticalAlign.TextBottom)
                ) {
                    val bmp by produceState<ImageBitmap?>(null, path) { value = loadImage(path, wPx) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) }
                            ?: CircularProgressIndicator(Modifier.size(24.dp), color = fg.copy(alpha = 0.4f), strokeWidth = 2.dp)
                    }
                }
            }.toMap()
        }
        val pages = remember(text, wPx, hPx, inline) {
            val placeholders = text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length)
                .mapNotNull { a -> inline[a.item]?.let { AnnotatedString.Range(it.placeholder, a.start, a.end) } }
            val layout = measurer.measure(text, style.copy(color = Color.Black), constraints = Constraints(maxWidth = wPx), placeholders = placeholders)
            paginate(layout, hPx)
        }
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
        val hasPrev = chapterIndex > 0
        val hasNext = chapterIndex < chapterCount - 1
        val lead = if (hasPrev) 1 else 0
        val count = pages.size + lead + (if (hasNext) 1 else 0)
        fun pageOf(off: Int) = pages.indexOfLast { it.first <= off }.coerceAtLeast(0)
        val initial = lead + if (startAtEnd) pages.lastIndex else pageOf(startOffset)
        val pager = rememberPagerState(initialPage = initial) { count }
        val scope = rememberCoroutineScope()

        LaunchedEffect(pager, pages) {
            snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { p ->
                val idx = p - lead
                when {
                    idx < 0 -> onPrevChapter()
                    idx >= pages.size -> onNextChapter()
                    else -> onPageSettled(pages[idx].first, pages[idx].last + 1, idx == pages.lastIndex)
                }
            }
        }
        LaunchedEffect(seek, pages) {
            val s = seek ?: return@LaunchedEffect
            val target = lead + pageOf(s.offset)
            if (target != pager.currentPage) pager.animateScrollToPage(target)
        }
        LaunchedEffect(pager) {
            VolumeKeys.events.collect { d ->
                val t = (pager.currentPage + d).coerceIn(0, count - 1)
                if (t != pager.currentPage) pager.animateScrollToPage(t)
            }
        }

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().pointerInput(pager) {
                detectTapGestures { pos ->
                    val w = size.width
                    when {
                        pos.x < w / 3f && layout.tapMode == TapMode.LEFT_RIGHT ->
                            scope.launch { if (pager.currentPage > 0) pager.animateScrollToPage(pager.currentPage - 1) }
                        pos.x < w / 3f -> scope.launch { if (pager.currentPage < count - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                        pos.x > w * 2f / 3f -> scope.launch { if (pager.currentPage < count - 1) pager.animateScrollToPage(pager.currentPage + 1) }
                        else -> onToggleMenu()
                    }
                }
            },
        ) { page ->
            val idx = page - lead
            Box(Modifier.fillMaxSize().pageEffect(pager, page, effect).background(bg)) {
                if (idx in pages.indices) {
                    val r = pages[idx]
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
                    // 书签标记
                    if (bookmarked(r.first, r.last + 1)) Icon(Icons.Rounded.Bookmark, "已加书签", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = hPad).size(24.dp))
                    Text(
                        pageText,
                        style = style,
                        inlineContent = inline,
                        modifier = Modifier.padding(start = hPad, end = hPad, top = topPad).width(with(density) { wPx.toDp() }),
                    )
                    val meta = MaterialTheme.typography.labelSmall.copy(color = fg.copy(alpha = 0.6f))
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = hPad).padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(chapter.title, style = meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        val pct = if (idx == pages.lastIndex && chapterIndex == chapterCount - 1) 1f else percentAt(r.first)
                        if (layout.showClock) {
                            Text(clock.first, style = meta)
                            Spacer(Modifier.width(8.dp))
                            if (clock.second >= 0) {
                                Icon(batteryIcon(clock.second), "电量", Modifier.size(12.dp), tint = meta.color)
                                Text("${clock.second}", style = meta)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Text("${idx + 1}/${pages.size} · ${"%.1f".format(pct * 100)}%", style = meta)
                    }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = fg.copy(alpha = 0.6f))
                        Spacer(Modifier.height(16.dp))
                        Text(if (idx < 0) "上一章" else "下一章", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.6f))
                    }
                }
            }
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

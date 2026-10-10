package com.tavi.novelreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tavi.novelreader.MainActivity
import com.tavi.novelreader.R
import com.tavi.novelreader.parser.BookSource
import com.tavi.novelreader.parser.OBJ
import com.tavi.novelreader.parser.plainText
import com.tavi.novelreader.parser.blockStarts
import com.tavi.novelreader.parser.tableText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

data class TtsState(
    val active: Boolean = false,
    val playing: Boolean = false,
    val bookId: Long = -1,
    val title: String = "",
    val chapter: Int = 0,
    val chapterTitle: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val error: String? = null,
    /** 定时关闭的截止时间（毫秒时间戳），0 表示未设置 */
    val sleepAt: Long = 0L,
)

/** 全局朗读控制器：使用系统 TextToSpeech，按段落排队朗读并自动翻章 */
object ReadAloud {
    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var source: BookSource? = null
    private var gen = 0
    private var rate = 1f
    private var pitch = 1f
    private var sleepJob: Job? = null

    /** 设置定时关闭；minutes = 0 取消 */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _state.value = _state.value.copy(sleepAt = 0L); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        _state.value = _state.value.copy(sleepAt = at)
        sleepJob = scope.launch { delay(minutes * 60_000L); pause(); _state.value = _state.value.copy(sleepAt = 0L) }
    }

    fun setPitch(p: Float) {
        pitch = p; tts?.setPitch(p)
        if (_state.value.playing) { val s = _state.value; gen++; tts?.stop(); speakFrom(s.chapter, s.start) }
    }
    private lateinit var app: Context

    private fun ensure(ctx: Context) {
        app = ctx.applicationContext
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            scope.launch {
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                        _state.value = _state.value.copy(error = "系统朗读引擎不支持中文，请在系统设置中安装中文语音包")
                    ready = true
                    pending?.invoke(); pending = null
                } else {
                    _state.value = TtsState(error = "无法初始化系统朗读引擎")
                    tts = null
                }
            }
        }
        tts?.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { scope.launch { onSegStart(id) } }
            override fun onDone(id: String) { scope.launch { onSegDone(id) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String) { scope.launch { onSegDone(id) } }
        })
    }

    fun start(ctx: Context, bookId: Long, title: String, src: BookSource, chapter: Int, offset: Int, speed: Float,
              pitch: Float = 1f, sleepMinutes: Int = 0) {
        ensure(ctx)
        source = src; rate = speed; this.pitch = pitch
        _state.value = TtsState(active = true, playing = true, bookId = bookId, title = title, chapter = chapter,
            chapterTitle = src.chapterTitles.getOrElse(chapter) { "" }, start = offset, end = offset)
        // 应用在前台时启动服务；media3 在开始播放后自动把它提升为前台服务并显示媒体通知
        runCatching { app.startService(Intent(app, ReadAloudService::class.java)) }
        requestFocus()
        setSleepTimer(sleepMinutes)
        val go = { speakFrom(chapter, offset) }
        if (ready) go() else pending = go
    }

    fun pause() {
        gen++; tts?.stop()
        if (!transientLoss) abandonFocus()
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        val s = _state.value
        if (!s.active || source == null) return
        if (!requestFocus()) return
        _state.value = s.copy(playing = true)
        speakFrom(s.chapter, s.start)
    }

    /** 上一章 / 下一章 */
    fun skipChapter(d: Int) {
        val s = _state.value; val src = source ?: return
        if (!s.active) return
        val n = s.chapter + d
        if (n !in src.chapterTitles.indices) return
        gen++; tts?.stop()
        _state.value = s.copy(chapter = n, start = 0, end = 0, chapterTitle = src.chapterTitles[n], playing = true)
        speakFrom(n, 0)
    }

    // ---------- 音频焦点 ----------
    private var focusReq: android.media.AudioFocusRequest? = null
    private var transientLoss = false
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { f ->
        scope.launch {
            when (f) {
                android.media.AudioManager.AUDIOFOCUS_LOSS -> { transientLoss = false; pause() }
                android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    if (_state.value.playing) { transientLoss = true; pause() }
                android.media.AudioManager.AUDIOFOCUS_GAIN -> if (transientLoss) { transientLoss = false; resume() }
            }
        }
    }
    private fun requestFocus(): Boolean {
        if (!::app.isInitialized) return true
        val am = app.getSystemService(android.media.AudioManager::class.java) ?: return true
        val req = focusReq ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(true).build().also { focusReq = it }
        return am.requestAudioFocus(req) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonFocus() {
        if (!::app.isInitialized) return
        focusReq?.let { app.getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(it) }
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun stop() {
        sleepJob?.cancel()
        gen++; tts?.stop()
        _state.value = TtsState()
        source = null
        transientLoss = false; abandonFocus()
        if (::app.isInitialized) app.stopService(Intent(app, ReadAloudService::class.java))
    }

    fun setRate(r: Float) {
        rate = r
        tts?.setSpeechRate(r)
        if (_state.value.playing) { val s = _state.value; gen++; tts?.stop(); speakFrom(s.chapter, s.start) }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private fun speakFrom(chapter: Int, offset: Int) {
        val src = source ?: return
        val my = ++gen
        scope.launch {
            val ch = withContext(Dispatchers.IO) { runCatching { src.loadChapter(chapter) }.getOrNull() } ?: return@launch
            val text = ch.plainText()
            if (my != gen) return@launch
            // 表格：以表格文字代替占位符朗读
            val tables = HashMap<Int, String>()
            val st = ch.blockStarts()
            ch.blocks.forEachIndexed { i, b -> if (b.kind == com.tavi.novelreader.parser.BlockKind.TABLE) tables[st[i]] = "表格：" + b.tableText() }
            val segs = (segments(text) + tables.keys.map { it..it }).sortedBy { it.first }.filter { it.last >= offset }
            val engine = tts ?: return@launch
            engine.setSpeechRate(rate); engine.setPitch(pitch)
            _state.value = _state.value.copy(chapter = chapter, chapterTitle = src.chapterTitles.getOrElse(chapter) { "" })
            if (segs.isEmpty()) { onSegDone("$my:$chapter:END"); return@launch }
            segs.forEachIndexed { i, r ->
                val s = maxOf(r.first, offset)
                val last = if (i == segs.lastIndex) ":L" else ""
                val say = tables[r.first] ?: speakable(text.substring(s, r.last + 1))
                engine.speak(say.ifBlank { " " }, TextToSpeech.QUEUE_ADD, null, "$my:$chapter:$s:${r.last + 1}$last")
            }
        }
    }

    /** 去掉列表符号与装饰性符号，避免被读出来 */
    fun speakable(t: String): String =
        t.replace(Regex("[•◦▪▫■□●○◆◇★☆※§¶†‡◎⊙✦✧❖♦♠♣♥❤✿❀＊*#＃~～_＿|｜=＝\\u2002\\u3000\\uFFFC\\u200B]+"), " ")
            .replace(Regex("[-—–─━]{2,}"), " ").replace(Regex("[.。…·・]{3,}"), "。").trim()

    /** 按段落、长段再按句子切分；跳过图片占位与空白 */
    private fun segments(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            var j = text.indexOf('\n', i); if (j < 0) j = text.length
            var s = i
            while (s < j) {
                var e = minOf(j, s + 200)
                if (e < j) {
                    val cut = (e downTo s + 20).firstOrNull { text[it - 1] in "。！？!?；;" }
                    if (cut != null) e = cut
                }
                if (text.substring(s, e).any { !it.isWhitespace() && it != OBJ && it != '\u3000' && it != '\u200B' }) out += s until e
                s = e
            }
            i = j + 1
        }
        return out
    }

    private fun parse(id: String): List<String>? = id.split(':').takeIf { it.size >= 3 && it[0].toIntOrNull() == gen }

    private fun onSegStart(id: String) {
        val p = parse(id) ?: return
        if (p.size < 4) return
        _state.value = _state.value.copy(chapter = p[1].toInt(), start = p[2].toInt(), end = p[3].toInt())
    }

    private fun onSegDone(id: String) {
        val p = parse(id) ?: return
        if (!(id.endsWith(":L") || p[2] == "END")) return
        val src = source ?: return
        val next = p[1].toInt() + 1
        if (next < src.chapterTitles.size) {
            _state.value = _state.value.copy(chapter = next, start = 0, end = 0)
            speakFrom(next, 0)
        } else stop()
    }
}

/** 把朗读状态暴露为 media3 播放器，系统媒体通知、锁屏、耳机 / 蓝牙按键都通过 MediaSession 控制它 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TtsPlayer : androidx.media3.common.SimpleBasePlayer(android.os.Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    init { scope.launch { ReadAloud.state.collect { invalidateState() } } }

    override fun getState(): State {
        val s = ReadAloud.state.value
        val cmds = androidx.media3.common.Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_GET_METADATA, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
        ).build()
        val b = State.Builder().setAvailableCommands(cmds)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (s.active) STATE_READY else STATE_IDLE)
        if (s.active) b.setPlaylist(listOf(
            MediaItemData.Builder("tts-${s.bookId}").setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(s.chapterTitle).setArtist(s.title)
                    .setAlbumTitle(s.title).setDisplayTitle(s.chapterTitle).build()
            ).setIsSeekable(false).build()
        ))
        return b.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): com.google.common.util.concurrent.ListenableFuture<*> {
        if (playWhenReady) ReadAloud.resume() else ReadAloud.pause()
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleStop(): com.google.common.util.concurrent.ListenableFuture<*> {
        ReadAloud.stop(); return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): com.google.common.util.concurrent.ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> ReadAloud.skipChapter(1)
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> ReadAloud.skipChapter(-1)
        }
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }

    override fun handleRelease(): com.google.common.util.concurrent.ListenableFuture<*> {
        scope.cancel(); return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}

class ReadAloudService : androidx.media3.session.MediaSessionService() {
    private var session: androidx.media3.session.MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        session = androidx.media3.session.MediaSession.Builder(this, TtsPlayer()).setSessionActivity(open).build()
        scope.launch { ReadAloud.state.collect { if (!it.active) { stopSelf() } } }
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!ReadAloud.state.value.playing) { ReadAloud.stop(); stopSelf() }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

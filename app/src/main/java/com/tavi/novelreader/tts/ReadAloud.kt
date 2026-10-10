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
        ContextCompat.startForegroundService(app, Intent(app, ReadAloudService::class.java))
        setSleepTimer(sleepMinutes)
        val go = { speakFrom(chapter, offset) }
        if (ready) go() else pending = go
    }

    fun pause() {
        gen++; tts?.stop()
        _state.value = _state.value.copy(playing = false)
    }

    fun resume() {
        val s = _state.value
        if (!s.active || source == null) return
        _state.value = s.copy(playing = true)
        speakFrom(s.chapter, s.start)
    }

    fun toggle() = if (_state.value.playing) pause() else resume()

    fun stop() {
        sleepJob?.cancel()
        gen++; tts?.stop()
        _state.value = TtsState()
        source = null
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
            val text = withContext(Dispatchers.IO) { runCatching { src.loadChapter(chapter).plainText() }.getOrNull() } ?: return@launch
            if (my != gen) return@launch
            val segs = segments(text).filter { it.last >= offset }
            val engine = tts ?: return@launch
            engine.setSpeechRate(rate); engine.setPitch(pitch)
            _state.value = _state.value.copy(chapter = chapter, chapterTitle = src.chapterTitles.getOrElse(chapter) { "" })
            if (segs.isEmpty()) { onSegDone("$my:$chapter:END"); return@launch }
            segs.forEachIndexed { i, r ->
                val s = maxOf(r.first, offset)
                val last = if (i == segs.lastIndex) ":L" else ""
                engine.speak(text.substring(s, r.last + 1), TextToSpeech.QUEUE_ADD, null, "$my:$chapter:$s:${r.last + 1}$last")
            }
        }
    }

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

class ReadAloudService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "听书", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, build(ReadAloud.state.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        scope.launch {
            ReadAloud.state.collectLatest { s ->
                if (!s.active) { stopSelf(); return@collectLatest }
                runCatching { NotificationManagerCompat.from(this@ReadAloudService).notify(ID, build(s)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> ReadAloud.toggle()
            ACT_STOP -> ReadAloud.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun build(s: TtsState): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifEmpty { "墨阅 · 听书" })
            .setContentText(s.chapterTitle)
            .setContentIntent(open)
            .setOngoing(s.playing)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (s.playing) "暂停" else "继续", pi(ACT_TOGGLE, 1))
            .addAction(0, "停止", pi(ACT_STOP, 2))
            .build()
    }

    companion object {
        const val CH = "read_aloud"; const val ID = 7
        const val ACT_TOGGLE = "toggle"; const val ACT_STOP = "stop"
    }
}

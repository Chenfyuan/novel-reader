package com.tavi.novelreader.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

enum class ReaderTheme(val label: String, val bg: Color, val fg: Color, val dark: Boolean) {
    LIGHT("明亮", Color(0xFFFDFBF7), Color(0xFF1F1B16), false),
    SEPIA("羊皮纸", Color(0xFFF3E7C9), Color(0xFF4A3B26), false),
    GREEN("护眼", Color(0xFFCCE8CF), Color(0xFF1E3A22), false),
    DARK("夜间", Color(0xFF121212), Color(0xFFB8B2A8), true),
}

enum class PageEffect(val label: String) { SLIDE("平移"), CUBE("3D 立方"), STACK("层叠翻页") }
enum class ShelfSort(val label: String) { RECENT_READ("最近阅读"), RECENT_ADDED("最近添加"), TITLE("书名") }
enum class DarkMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class ContrastLevel(val label: String, val value: Double) { STANDARD("标准", 0.0), MEDIUM("中", 0.5), HIGH("高", 1.0) }
enum class ReaderFont(val label: String) { SYSTEM("系统默认"), SERIF("衬线"), CUSTOM("导入字体") }
enum class TapMode(val label: String) { LEFT_RIGHT("左右分区"), ALL_NEXT("全屏下一页") }

/** 预设种子色 */
val SeedColors = listOf(
    0xFF6750A4L to "紫罗兰", 0xFF0061A4L to "海蓝", 0xFF006A6AL to "青碧", 0xFF386A20L to "竹青",
    0xFF8B5000L to "琥珀", 0xFFB3261EL to "朱红", 0xFF984061L to "胭脂", 0xFF5C5F61L to "石墨",
    0xFF7D5700L to "姜黄", 0xFF4355B9L to "靛青",
)

data class ReaderSettings(
    // 外观
    val dynamicColor: Boolean = true,
    val seedColor: Long = 0xFF6750A4L,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val amoled: Boolean = false,
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    // 阅读
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.7f,
    val paragraphSpacing: Float = 0.5f, // 以字号为单位
    val indent: Boolean = true,
    val pageMargin: Int = 24, // dp
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val effect: PageEffect = PageEffect.STACK,
    val font: ReaderFont = ReaderFont.SYSTEM,
    val customFontPath: String? = null,
    val customFontName: String? = null,
    val traditional: Boolean = false,
    val zhVariant: com.tavi.novelreader.parser.ZhVariant = com.tavi.novelreader.parser.ZhVariant.S2T,
    /** 上下滚动阅读 */
    val scrollMode: Boolean = false,
    val tapMode: TapMode = TapMode.LEFT_RIGHT,
    val brightness: Float = -1f,
    val volumeKeys: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showClock: Boolean = true,
    // 听书
    val ttsRate: Float = 1f,
    val ttsPitch: Float = 1f,
    val sleepMinutes: Int = 0, // 0 = 关闭
    // 书架
    val shelfSort: ShelfSort = ShelfSort.RECENT_READ,
    val shelfGrid: Boolean = true,
    val gridColumns: Int = 0, // 0 = 自动
    val coverProgress: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dynamicColor", dynamicColor); put("seedColor", seedColor); put("darkMode", darkMode.name); put("amoled", amoled)
        put("contrast", contrast.name); put("fontSize", fontSize.toDouble()); put("lineSpacing", lineSpacing.toDouble())
        put("paragraphSpacing", paragraphSpacing.toDouble()); put("indent", indent); put("pageMargin", pageMargin)
        put("theme", theme.name); put("effect", effect.name); put("font", font.name)
        put("customFontPath", customFontPath ?: ""); put("customFontName", customFontName ?: "")
        put("traditional", traditional); put("zhVariant", zhVariant.name); put("scrollMode", scrollMode); put("tapMode", tapMode.name); put("brightness", brightness.toDouble())
        put("volumeKeys", volumeKeys); put("keepScreenOn", keepScreenOn); put("showClock", showClock)
        put("ttsRate", ttsRate.toDouble()); put("ttsPitch", ttsPitch.toDouble()); put("sleepMinutes", sleepMinutes)
        put("shelfSort", shelfSort.name); put("shelfGrid", shelfGrid); put("gridColumns", gridColumns); put("coverProgress", coverProgress)
    }

    companion object {
        private inline fun <reified E : Enum<E>> JSONObject.enum(k: String, d: E): E =
            runCatching { enumValueOf<E>(getString(k)) }.getOrDefault(d)

        fun fromJson(j: JSONObject): ReaderSettings {
            val d = ReaderSettings()
            return ReaderSettings(
                dynamicColor = j.optBoolean("dynamicColor", d.dynamicColor), seedColor = j.optLong("seedColor", d.seedColor),
                darkMode = j.enum("darkMode", d.darkMode), amoled = j.optBoolean("amoled", d.amoled), contrast = j.enum("contrast", d.contrast),
                fontSize = j.optDouble("fontSize", d.fontSize.toDouble()).toFloat(),
                lineSpacing = j.optDouble("lineSpacing", d.lineSpacing.toDouble()).toFloat(),
                paragraphSpacing = j.optDouble("paragraphSpacing", d.paragraphSpacing.toDouble()).toFloat(),
                indent = j.optBoolean("indent", d.indent), pageMargin = j.optInt("pageMargin", d.pageMargin),
                theme = j.enum("theme", d.theme), effect = j.enum("effect", d.effect), font = j.enum("font", d.font),
                customFontPath = j.optString("customFontPath").ifEmpty { null }, customFontName = j.optString("customFontName").ifEmpty { null },
                traditional = j.optBoolean("traditional", d.traditional), zhVariant = j.enum("zhVariant", d.zhVariant), scrollMode = j.optBoolean("scrollMode", d.scrollMode), tapMode = j.enum("tapMode", d.tapMode),
                brightness = j.optDouble("brightness", d.brightness.toDouble()).toFloat(),
                volumeKeys = j.optBoolean("volumeKeys", d.volumeKeys), keepScreenOn = j.optBoolean("keepScreenOn", d.keepScreenOn),
                showClock = j.optBoolean("showClock", d.showClock),
                ttsRate = j.optDouble("ttsRate", d.ttsRate.toDouble()).toFloat(), ttsPitch = j.optDouble("ttsPitch", d.ttsPitch.toDouble()).toFloat(),
                sleepMinutes = j.optInt("sleepMinutes", d.sleepMinutes),
                shelfSort = j.enum("shelfSort", d.shelfSort), shelfGrid = j.optBoolean("shelfGrid", d.shelfGrid),
                gridColumns = j.optInt("gridColumns", d.gridColumns), coverProgress = j.optBoolean("coverProgress", d.coverProgress),
            )
        }
    }
}

private val Context.store by preferencesDataStore("settings")
private val KEY = stringPreferencesKey("settings_json")

/** 设置仓库：DataStore 持久化（整份设置存为 JSON），对外暴露同步可读的 StateFlow */
class SettingsRepo(ctx: Context) {
    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ReaderSettings> = _state

    private fun load(): ReaderSettings = runBlocking {
        val json = runCatching { app.store.data.first()[KEY] }.getOrNull()
        if (json != null) runCatching { ReaderSettings.fromJson(JSONObject(json)) }.getOrDefault(ReaderSettings())
        else migrateFromSharedPrefs()
    }

    /** 从 1.2 及以前的 SharedPreferences 迁移 */
    private fun migrateFromSharedPrefs(): ReaderSettings {
        val sp = app.getSharedPreferences("reader", Context.MODE_PRIVATE)
        if (sp.all.isEmpty()) return ReaderSettings()
        val d = ReaderSettings()
        fun <E : Enum<E>> e(v: String?, all: Array<E>, def: E) = all.firstOrNull { it.name == v } ?: def
        return d.copy(
            fontSize = sp.getFloat("fs", d.fontSize), lineSpacing = sp.getFloat("ls", d.lineSpacing),
            theme = e(sp.getString("th", null), ReaderTheme.entries.toTypedArray(), d.theme),
            effect = e(sp.getString("ef", null), PageEffect.entries.toTypedArray(), d.effect),
            brightness = sp.getFloat("br", -1f), volumeKeys = sp.getBoolean("vk", false), keepScreenOn = sp.getBoolean("ko", true),
            ttsRate = sp.getFloat("tr", 1f), shelfSort = e(sp.getString("ss", null), ShelfSort.entries.toTypedArray(), d.shelfSort),
            shelfGrid = sp.getBoolean("sg", true),
        ).also { save(it) }
    }

    private fun save(s: ReaderSettings) = scope.launch { app.store.edit { it[KEY] = s.toJson().toString() } }

    fun update(f: (ReaderSettings) -> ReaderSettings) {
        val n = f(_state.value)
        if (n == _state.value) return
        _state.value = n
        save(n)
    }

    fun replace(s: ReaderSettings) { _state.value = s; save(s) }
}

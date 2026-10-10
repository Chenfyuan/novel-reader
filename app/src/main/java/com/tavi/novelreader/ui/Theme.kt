package com.tavi.novelreader.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.tavi.novelreader.data.DarkMode
import com.tavi.novelreader.data.ReaderSettings
import androidx.compose.ui.platform.LocalContext

/** Material 3 动效令牌（m3.material.io/styles/motion） */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val Short4 = 200
    const val Medium2 = 300
    const val Medium4 = 400
    const val Long2 = 500
    fun <T> emphasized(d: Int = Long2): FiniteAnimationSpec<T> = tween(d, easing = Emphasized)
    fun <T> standard(d: Int = Medium2): FiniteAnimationSpec<T> = tween(d, easing = Standard)
}

fun isDark(settings: ReaderSettings, systemDark: Boolean) = when (settings.darkMode) {
    DarkMode.SYSTEM -> systemDark
    DarkMode.LIGHT -> false
    DarkMode.DARK -> true
}

val dynamicColorSupported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun NovelTheme(
    darkTheme: Boolean? = null,
    dynamicColor: Boolean? = null,
    settings: ReaderSettings = ReaderSettings(),
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val dark = darkTheme ?: isDark(settings, isSystemInDarkTheme())
    val useDynamic = (dynamicColor ?: settings.dynamicColor) && dynamicColorSupported
    val target = remember(dark, useDynamic, settings.seedColor, settings.amoled, settings.contrast) {
        val base = if (useDynamic) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else dynamicColorScheme(
            seedColor = Color(settings.seedColor), isDark = dark, isAmoled = settings.amoled && dark,
            style = PaletteStyle.TonalSpot, contrastLevel = settings.contrast.value,
        )
        if (useDynamic && dark && settings.amoled) base.copy(
            background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0B), surfaceContainer = Color(0xFF121212),
        ) else base
    }
    MaterialTheme(colorScheme = target.animated(), typography = Typography(), shapes = Shapes(), content = content)
}

/** 主题切换时所有颜色角色平滑过渡 */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = Motion.standard<Color>(Motion.Long2)
    @Composable fun a(c: Color) = animateColorAsState(c, spec, label = "c").value
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer), onPrimaryContainer = a(onPrimaryContainer),
        inversePrimary = a(inversePrimary),
        secondary = a(secondary), onSecondary = a(onSecondary), secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary), onTertiary = a(onTertiary), tertiaryContainer = a(tertiaryContainer), onTertiaryContainer = a(onTertiaryContainer),
        background = a(background), onBackground = a(onBackground), surface = a(surface), onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant), surfaceTint = a(surfaceTint),
        inverseSurface = a(inverseSurface), inverseOnSurface = a(inverseOnSurface),
        outline = a(outline), outlineVariant = a(outlineVariant),
        surfaceBright = a(surfaceBright), surfaceDim = a(surfaceDim),
        surfaceContainerLowest = a(surfaceContainerLowest), surfaceContainerLow = a(surfaceContainerLow),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh), surfaceContainerHighest = a(surfaceContainerHighest),
    )
}

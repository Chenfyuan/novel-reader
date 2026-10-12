package com.tavi.novelreader.ui

import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.tasks.await

/** 划词查询的目标语言 */
private val Targets = listOf(TranslateLanguage.CHINESE to "中文", TranslateLanguage.ENGLISH to "English", TranslateLanguage.JAPANESE to "日本語")
private fun langName(code: String) = when (code) {
    TranslateLanguage.CHINESE -> "中文"; TranslateLanguage.ENGLISH -> "英语"; TranslateLanguage.JAPANESE -> "日语"
    TranslateLanguage.KOREAN -> "韩语"; TranslateLanguage.FRENCH -> "法语"; TranslateLanguage.GERMAN -> "德语"
    else -> code
}

/**
 * 查词 / 翻译底部面板：
 * - 翻译：ML Kit 端侧翻译，首次使用某语言时下载离线模型（约 30 MB），之后离线可用；
 * - 释义：交给本机已安装的词典 / 翻译应用（系统「处理文本」PROCESS_TEXT 接口）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LookupSheet(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val query = text.trim().take(2000)
    var src by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在识别语言…") }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(query) {
        val id = runCatching { LanguageIdentification.getClient().identifyLanguage(query).await() }.getOrDefault("und")
        // 含假名视为日语，含汉字视为中文（短词时语言识别不稳定）
        val guess = when {
            query.any { it in '\u3040'..'\u30ff' } -> TranslateLanguage.JAPANESE
            id == "und" && query.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> TranslateLanguage.CHINESE
            id == "und" -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.fromLanguageTag(id.substringBefore('-')) ?: TranslateLanguage.ENGLISH
        }
        src = guess
        target = if (guess == TranslateLanguage.CHINESE) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE
    }
    LaunchedEffect(src, target, retry) {
        val s = src ?: return@LaunchedEffect; val t = target ?: return@LaunchedEffect
        result = null
        if (s == t) { result = query; status = ""; return@LaunchedEffect }
        val tr = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        try {
            status = "正在准备离线翻译模型（首次使用需下载约 30 MB）…"
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            status = "正在翻译…"
            result = tr.translate(query).await(); status = ""
        } catch (e: Exception) {
            status = "翻译失败：${e.message ?: "模型下载未完成，请检查网络后重试"}"
        } finally { tr.close() }
    }

    val apps = remember {
        val probe = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        ctx.packageManager.queryIntentActivities(probe, 0).filter { it.activityInfo.packageName != ctx.packageName }
    }

    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text("查词与翻译", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(query, style = MaterialTheme.typography.bodyLarge, maxLines = 6)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${src?.let(::langName) ?: "…"}  →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Targets.forEach { (code, label) ->
                    FilterChip(target == code, { target = code }, { Text(label) }, Modifier.padding(end = 8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val r = result
                    if (r != null) SelectionContainer { Text(r, style = MaterialTheme.typography.bodyLarge) }
                    else if (status.startsWith("翻译失败")) {
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton({ retry++ }) { Text("重试") }
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("离线翻译，由设备端模型提供", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(20.dp))
            Text("释义", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (apps.isEmpty()) {
                Text("本机没有可处理文字的词典或翻译应用。安装支持「文本处理」的词典应用（如欧路词典、有道词典、Google 翻译等）后，可以在这里直接查释义。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else apps.forEach { ri -> ProcessTextItem(ri, query) }
        }
    }
}

@Composable
private fun ProcessTextItem(ri: ResolveInfo, query: String) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val label = remember(ri) { ri.loadLabel(pm).toString() }
    val icon = remember(ri) { runCatching { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    ListItem(
        headlineContent = { Text("用「$label」查询") },
        leadingContent = { if (icon != null) androidx.compose.foundation.Image(icon, null, Modifier.size(32.dp)) else Icon(Icons.Rounded.Translate, null) },
        trailingContent = { Icon(Icons.Rounded.OpenInNew, null) },
        modifier = Modifier.clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, query).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
            }
        },
    )
}

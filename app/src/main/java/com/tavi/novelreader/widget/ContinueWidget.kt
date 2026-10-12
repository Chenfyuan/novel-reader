package com.tavi.novelreader.widget

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tavi.novelreader.MainActivity
import com.tavi.novelreader.data.AppDb
import com.tavi.novelreader.data.Book
import java.io.File

/** 桌面小组件：显示最近在读的书（封面、书名、进度），点按直接继续阅读 */
class ContinueWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val book = runCatching { AppDb.get(context).books().lastReadBook() }.getOrNull()
        val cover = book?.coverPath?.let { p ->
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(p, o)
                var s = 1; while (o.outHeight / (s * 2) >= 300) s *= 2
                BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
        provideContent { GlanceTheme { Content(context, book, cover) } }
    }

    @Composable
    private fun Content(ctx: Context, book: Book?, cover: android.graphics.Bitmap?) {
        val open = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_BOOK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (book != null) putExtra(MainActivity.EXTRA_BOOK, book.id) }
        Row(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).appWidgetBackground()
                .cornerRadius(24.dp).padding(12.dp).clickable(actionStartActivity(open)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (book == null) {
                Text("墨阅 · 还没有在读的书", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                return@Row
            }
            if (cover != null) Image(ImageProvider(cover), book.title, GlanceModifier.width(56.dp).height(84.dp).cornerRadius(8.dp),
                contentScale = androidx.glance.layout.ContentScale.Crop)
            else Box(GlanceModifier.width(56.dp).height(84.dp).cornerRadius(8.dp).background(GlanceTheme.colors.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(book.title.take(2), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontWeight = FontWeight.Bold))
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("继续阅读", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                Text(book.title, maxLines = 2, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                if (book.author.isNotBlank()) Text(book.author, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(book.progress.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth(),
                    color = GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.surfaceVariant)
                Spacer(GlanceModifier.height(4.dp))
                Text("已读 %.1f%%".format(book.progress * 100), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }

    companion object {
        suspend fun refresh(ctx: Context) = runCatching { ContinueWidget().updateAll(ctx) }
    }
}

class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWidget()
}

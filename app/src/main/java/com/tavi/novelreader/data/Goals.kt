package com.tavi.novelreader.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tavi.novelreader.MainActivity
import com.tavi.novelreader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 阅读目标与连续打卡：由每日阅读时长（reading_days，已参与同步与备份）推算，不额外存储 */
data class Streak(val current: Int, val best: Int, val todayMs: Long, val goalMs: Long, val doneToday: Boolean)

object Goals {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    fun dayKey(c: Calendar): String = fmt().format(c.time)

    /** 达标日 = 当日阅读时长 ≥ 目标；当前连续天数：今天已达标则从今天往前数，否则从昨天往前数（今天还有机会） */
    fun streak(days: Map<String, Long>, goalMinutes: Int, today: Calendar = Calendar.getInstance()): Streak {
        val goal = goalMinutes.coerceAtLeast(1) * 60_000L
        fun met(k: String) = (days[k] ?: 0L) >= goal
        val todayKey = dayKey(today)
        val c = today.clone() as Calendar
        if (!met(todayKey)) c.add(Calendar.DAY_OF_YEAR, -1)
        var cur = 0
        while (met(dayKey(c))) { cur++; c.add(Calendar.DAY_OF_YEAR, -1) }
        // 最长连续：按日期排序的达标日中最长的连续段
        val f = fmt()
        val metDays = days.filter { it.value >= goal }.keys.mapNotNull { runCatching { f.parse(it)!!.time }.getOrNull() }.sorted()
        var best = 0; var run = 0; var prev = Long.MIN_VALUE
        for (t in metDays) {
            val gap = Math.round((t - prev) / 86_400_000.0)
            run = if (prev != Long.MIN_VALUE && gap == 1L) run + 1 else 1
            best = maxOf(best, run); prev = t
        }
        return Streak(cur, maxOf(best, cur), days[todayKey] ?: 0L, goal, met(todayKey))
    }

    // ---------- 每日提醒 ----------
    private const val CHANNEL = "goal"
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, GoalReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 按设置安排下一次提醒（不精确闹钟，无需精确闹钟权限）；关闭时取消 */
    fun schedule(ctx: Context, s: ReaderSettings) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(ctx))
        if (!s.goalReminder) return
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.reminderAt / 60); set(Calendar.MINUTE, s.reminderAt % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 1000) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pending(ctx))
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(ctx: Context, minutesLeft: Long, streak: Int) {
        if (!canNotify(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "阅读目标提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (streak > 0) "已连续达标 $streak 天，今天还差 $minutesLeft 分钟" else "今天还差 $minutesLeft 分钟完成阅读目标"
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("读一会儿书吧")
            .setContentText(text).setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(2001, n) }
    }
}

/** 到点检查今天是否已达标，未达标才提醒；随后安排明天的提醒 */
class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsRepo(app).state.value
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) { Goals.schedule(app, s); return@launch }
                val days = AppDb.get(app).books().allDays().associate { it.day to it.millis }
                val st = Goals.streak(days, s.goalMinutes)
                if (s.goalReminder && !st.doneToday) Goals.notify(app, ((st.goalMs - st.todayMs) / 60_000L).coerceAtLeast(1), st.current)
                Goals.schedule(app, s)
            } finally { done.finish() }
        }
    }
}

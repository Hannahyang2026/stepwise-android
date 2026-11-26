package com.hannah.stepwise

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.ZonedDateTime

// 时间与开关沿用旧版首选项。各渠道独立去重，前台横幅不依赖系统通知权限。
data class Reminder(val enabled: Boolean = false, val hour: Int = 19, val minute: Int = 0)
data class ReminderNotice(val day: Long, val count: Int)
internal fun reminderDue(setting: Reminder, now: LocalTime): Boolean =
    setting.enabled && !now.isBefore(LocalTime.of(setting.hour, setting.minute))

object Reminders {
    const val channel = "daily"
    private val gate = Mutex()
    private val banner = MutableStateFlow<ReminderNotice?>(null)
    val notice = banner.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("reminder", Context.MODE_PRIVATE)

    fun read(context: Context): Reminder = prefs(context).let {
        Reminder(it.getBoolean("enabled", false), it.getInt("hour", 19), it.getInt("minute", 0))
    }

    fun save(context: Context, value: Reminder) {
        prefs(context).edit {
            putBoolean("enabled", value.enabled)
            putInt("hour", value.hour)
            putInt("minute", value.minute)
        }
        if (!value.enabled || !reminderDue(value, LocalTime.now())) banner.value = null
        schedule(context)
    }

    fun dismiss(context: Context, day: Long) {
        prefs(context).edit { putLong("dismissed", day) }
        if (banner.value?.day == day) banner.value = null
    }

    fun channel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "每日待办提醒", NotificationManager.IMPORTANCE_HIGH))
        return manager.getNotificationChannel(channel)
    }

    // 智能体任务仍使用它自己的类别。每日提醒另外检查 daily 类别是否被关闭。
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun exact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fallback = PendingIntent.getBroadcast(context, 41, Intent(context, ReminderReceiver::class.java), flags)
        val precise = PendingIntent.getBroadcast(context, 44, Intent(context, ReminderReceiver::class.java), flags)
        manager.cancel(fallback)
        manager.cancel(precise)
        val setting = read(context)
        if (!setting.enabled) return
        val now = ZonedDateTime.now()
        val time = LocalTime.of(setting.hour, setting.minute)
        val todayAtTime = now.toLocalDate().atTime(time).atZone(now.zone)
        val next = if (todayAtTime > now) todayAtTime else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
        val instant = next.toInstant().toEpochMilli()
        // 保留独立的可延迟闹钟。系统撤销精确权限时，仍有已排好的回退动作。
        val preciseScheduled = exact(context) && runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant, precise)
        }.isSuccess
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant + if (preciseScheduled) 60_000 else 0, fallback)
    }

    // 前台时钟、闹钟广播和启动补查共用这里。同一日期在两个入口同时触发时也只发一次系统通知。
    suspend fun check(context: Context) = gate.withLock {
        val now = ZonedDateTime.now()
        val day = now.toLocalDate().toEpochDay()
        if (!reminderDue(read(context), now.toLocalTime())) {
            banner.value = null
            return@withLock
        }
        val snapshot = Store(context).snapshot(day)
        val count = snapshot.rows(day).count { !it.done } + Workspace(context).dao.pending(day).size
        val preferences = prefs(context)
        banner.value = if (count > 0 && preferences.getLong("dismissed", Long.MIN_VALUE) != day)
            ReminderNotice(day, count) else null
        if (count > 0 && preferences.getLong("sent", Long.MIN_VALUE) != day &&
            post(context, 43, "一步 · 每日待办提醒", "今天还有 $count 项未完成")) {
            preferences.edit(commit = true) { putLong("sent", day) }
        }
    }

    fun test(context: Context): Boolean = post(context, 45, "一步 · 测试通知", "每日待办通知可以显示")

    private fun post(context: Context, id: Int, title: String, message: String): Boolean {
        if (!allowed(context) || channel(context).importance == NotificationManager.IMPORTANCE_NONE) return false
        val open = PendingIntent.getActivity(context, 42, Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("today", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_step)
            .setContentTitle(title).setContentText(message).setAutoCancel(true).setContentIntent(open).build()
        return runCatching { context.getSystemService(NotificationManager::class.java).notify(id, notice) }.isSuccess
    }
}

// 广播查询本地数据库时申请异步令牌，结束后及时交回系统。
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Reminders.check(context) } finally { pending.finish() }
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clockChanged = when (intent.action) {
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> true
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> false
            else -> return
        }
        Reminders.schedule(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.check(context)
                // 手机重启或覆盖升级后，先完成上次遗留的助手清理，再恢复仍有效的计划。
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
                    Workspace(context).resumeAssistantCleanup()
                if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
                    AssistantJobs(context).restoreAll(reset = clockChanged)
            } finally { pending.finish() }
        }
    }
}

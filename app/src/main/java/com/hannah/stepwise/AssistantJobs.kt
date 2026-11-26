package com.hannah.stepwise

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

// 计算手机日历中的最近到期时段和下一时段。错过多个周期时，只运行最后一个到期时段。
internal object JobClock {
    fun latest(first: LocalDateTime, repeat: String, now: LocalDateTime): LocalDateTime? {
        if (now < first) return null
        val days = when (repeat) { "daily" -> 1L; "weekly" -> 7L; else -> return first }
        val intervals = ChronoUnit.DAYS.between(first, now) / days
        return first.plusDays(intervals * days)
    }

    fun next(slot: LocalDateTime, repeat: String): LocalDateTime? = when (repeat) {
        "daily" -> slot.plusDays(1)
        "weekly" -> slot.plusWeeks(1)
        else -> null
    }
}

internal class AssistantJobs(private val context: Context) {
    private val work = Workspace(context)
    private val dao = work.dao
    private val manager = WorkManager.getInstance(context)
    private fun tag(id: Long) = "assistant-job-$id"

    // 不同的计划时段各有唯一工作名。联网限制由 Android 处理，开始前再次核对任务版本。
    suspend fun enqueue(job: AssistantJob, slot: LocalDateTime) = work.whileActive(job) {
        val zone = ZoneId.systemDefault()
        val delay = Duration.between(LocalDateTime.now().atZone(zone), slot.atZone(zone)).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<AssistantWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(tag(job.id))
            .setInputData(workDataOf("id" to job.id, "revision" to job.revision, "slot" to slot.toString()))
            .build()
        manager.enqueueUniqueWork("${tag(job.id)}-${job.revision}-$slot", ExistingWorkPolicy.KEEP, request)
    }

    suspend fun restore(job: AssistantJob) {
        if (!job.enabled) return
        val first = LocalDateTime.parse(job.first)
        val last = JobClock.latest(first, job.repeat, LocalDateTime.now())
        val due = when {
            last == null -> first
            dao.run(job.id, last.toString()) == null -> last
            else -> JobClock.next(last, job.repeat)
        }
        due?.let { enqueue(job, it) }
    }

    suspend fun replace(job: AssistantJob) {
        withContext(Dispatchers.IO) { manager.cancelAllWorkByTag(tag(job.id)).result.get() }
        restore(job)
    }

    suspend fun restoreAll(reset: Boolean = false) {
        // 旧版记录未关联工作 ID。新版发现工作已结束或被清理时，结束遗留的运行状态。
        dao.interruptLegacyRuns()
        for (run in dao.activeRuns()) {
            val owner = run.workerId ?: continue
            val state = withContext(Dispatchers.IO) {
                manager.getWorkInfoById(java.util.UUID.fromString(owner)).get()?.state
            }
            if (state == null || state.isFinished) dao.interruptWorker(owner)
        }
        dao.enabledJobs().forEach { if (reset) replace(it) else restore(it) }
    }

    fun cancel(id: Long) { manager.cancelAllWorkByTag(tag(id)) }

    // 删除助手时等到 WorkManager 持久化取消结果，再移除待清理凭据。
    suspend fun cancelAndWait(id: Long) = withContext(Dispatchers.IO) {
        manager.cancelAllWorkByTag(tag(id)).result.get()
    }
}

internal fun jobNoticeId(run: JobRun): Int = "job-${run.jobId}-${run.slot}".hashCode()

// SQLite 的时段主键是运行凭据。任务遇到中断时保留状态，避免再次提交可能已收费的同一请求。
class AssistantWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val work = Workspace(applicationContext)
        val dao = work.dao
        val job = dao.job(inputData.getLong("id", 0)) ?: return Result.success()
        // 重试优先结算本实例原先领取的时段，即使系统时间已推进到下一周期。
        val manager = AssistantJobs(applicationContext)
        if (runAttemptCount > 0 && dao.interruptWorker(id.toString()) > 0) {
            manager.restore(job)
            return Result.success()
        }
        if (!job.enabled || job.revision != inputData.getLong("revision", -1)) return Result.success()
        val planned = LocalDateTime.parse(inputData.getString("slot") ?: return Result.failure())
        val now = LocalDateTime.now()
        if (now < planned) return Result.retry()
        val latest = JobClock.latest(LocalDateTime.parse(job.first), job.repeat, now) ?: return Result.success()
        var run = JobRun(job.id, latest.toString(), job.agentId, job.name, "运行中", workerId = id.toString())
        val claimed = work.claim(job, run)
        JobClock.next(latest, job.repeat)?.let { manager.enqueue(job, it) }
        if (!claimed) return Result.success()
        try {
            val agent = dao.agent(job.agentId) ?: error("找不到智能体")
            val text = AssistantEngine(applicationContext).run(agent,
                listOf(AssistantWire.message("user", "当前时间 $now\n${job.prompt}")),
                if (job.agenda) AssistantWire.agendaRules else null,
                background = jsonStrings(job.tools),
                log = { line ->
                    run = run.copy(tools = listOf(run.tools, line).filter { it.isNotBlank() }.joinToString("\n"))
                    dao.update(run)
                })
            val batch = if (job.agenda) {
                val items = CalendarWire.parse(text)
                AgendaBatch("job-${job.id}-${run.slot}", agent.id, CalendarWire.encode(items))
            } else null
            run = run.copy(status = "已完成", result = if (batch == null) text else "日程草稿已生成",
                batchId = batch?.id)
            if (!work.finish(job, run, batch)) return Result.success()
        } catch (cancel: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                dao.update(run.copy(status = "运行中断", result = "任务已中断"))
            }
            throw cancel
        } catch (error: Exception) {
            run = run.copy(status = "失败", result = assistantError(error))
            if (!work.finish(job, run, null)) return Result.success()
        }
        work.whileActive(job) { notifyResult(run) }
        return Result.success()
    }

    private fun notifyResult(run: JobRun) {
        val context = applicationContext
        if (!Reminders.allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "assistant-jobs"
        manager.createNotificationChannel(NotificationChannel(channel, "智能体定时任务", NotificationManager.IMPORTANCE_DEFAULT))
        val id = jobNoticeId(run)
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("assistantId", run.agentId)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_step)
            .setContentTitle(run.name).setContentText(if (run.batchId != null) "日程草稿可预览" else "智能体任务 · ${run.status}")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

internal fun assistantError(error: Exception): String = when (error) {
    is java.io.IOException -> "网络或服务连接失败，请检查接口与网络"
    is org.json.JSONException -> "服务返回格式有误，请重试"
    else -> error.message ?: "操作失败，请重试"
}

package com.hannah.stepwise

import android.content.Context
import androidx.core.content.edit
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

// ID 0 留给内置规划。Room 从 1 开始分配真实助手 ID，因此两类草稿可以共表读取。
internal const val plannerAgentId = 0L
// 手动填写也经过草稿预览。该前缀区分手动来源，确认后不会被列入 AI 批次筛选。
internal const val manualDraftPrefix = "manual-"

// 日期事项和定时事项共用一张表。start/end 是相对本地日历原点的分钟，空值表示全天待办。
@Entity(indices = [Index("day"), Index("start")])
data class CalendarEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, val day: Long, val start: Long? = null, val end: Long? = null,
    val place: String = "", val kind: String = "event", val done: Boolean = false,
    @ColumnInfo(defaultValue = "''") val note: String = "",
    // 手动事项没有批次。AI 草稿确认时记录批次 ID，随后编辑仍沿用这个来源。
    val batchId: String? = null
) : java.io.Serializable // 让 Android 在旋转屏幕时保存当前打开的事项表单。

@Entity
data class AgendaBatch(
    @PrimaryKey val id: String, val agentId: Long, val items: String, val saved: Boolean = false,
    // 旧版批次只存草稿正文。新版使用这一标记区分已关联的批次和只能给出候选的旧批次。
    @ColumnInfo(defaultValue = "0") val linked: Boolean = false
)

// 批量管理用序号区分内容相同的多份草稿，实际查询和删除使用各自的 ID。
data class SavedBatch(val sequence: Long, val id: String, val items: String, val linked: Boolean)

@Entity
data class AssistantProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "学习助手", val model: String = "", val temperature: Double? = null,
    val effort: String = "", val prompt: String = ""
)

@Entity(indices = [Index("agentId")])
data class AssistantMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val agentId: Long,
    val role: String, val text: String
)

@Entity(indices = [Index("agentId")])
data class AssistantMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val agentId: Long, val text: String
)

// MCP 连接记录归属一个智能体。工具清单和授权指纹以 JSON 保存，令牌独立加密。
@Entity(indices = [Index("agentId")])
data class McpEndpoint(
    @PrimaryKey val id: String, val agentId: Long, val name: String, val url: String,
    val enabledTools: String = "[]"
)

@Entity(indices = [Index("agentId")])
data class AssistantJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val agentId: Long, val name: String,
    val prompt: String, val first: String, val repeat: String, val agenda: Boolean,
    val tools: String, val enabled: Boolean = true, val revision: Long = 1
)

@Entity(primaryKeys = ["jobId", "slot"], indices = [Index("agentId")])
data class JobRun(
    val jobId: Long, val slot: String, val agentId: Long, val name: String,
    val status: String, val result: String = "", val tools: String = "", val batchId: String? = null,
    // WorkManager 的工作 ID 关联实际运行的时段，包含跨多天补跑的情形。
    val workerId: String? = null
)

@Dao
interface WorkspaceDao {
    // Flow 是数据库订阅。当后台保存新结果时，相关界面会自动收到更新。
    @Query("SELECT * FROM CalendarEntry ORDER BY day, start, id") fun calendar(): Flow<List<CalendarEntry>>
    @Query("SELECT * FROM CalendarEntry WHERE start IS NULL AND day = :day AND done = 0")
    suspend fun pending(day: Long): List<CalendarEntry>
    @Update suspend fun update(entry: CalendarEntry)
    @Delete suspend fun delete(entry: CalendarEntry)
    // 批量删除只接收已复核的行 ID。分段取数以兼容 SQLite 的绑定参数上限。
    @Query("SELECT * FROM CalendarEntry WHERE id IN (:ids)")
    suspend fun entriesByIds(ids: List<Long>): List<CalendarEntry>
    @Query("DELETE FROM CalendarEntry WHERE id IN (:ids)")
    suspend fun deleteEntries(ids: List<Long>): Int
    @Insert suspend fun addEntries(items: List<CalendarEntry>)
    @Query("UPDATE CalendarEntry SET done = :done WHERE id = :id AND start IS NULL")
    suspend fun mark(id: Long, done: Boolean)
    @Query("SELECT * FROM AgendaBatch WHERE id = :id") suspend fun batch(id: String): AgendaBatch?
    @Query("SELECT * FROM AgendaBatch WHERE (agentId = :id OR agentId = 0) AND saved = 0 ORDER BY rowid DESC")
    fun batches(id: Long): Flow<List<AgendaBatch>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(batch: AgendaBatch)
    @Query("SELECT rowid AS sequence, id, items, linked FROM AgendaBatch WHERE saved = 1 AND id NOT GLOB '$manualDraftPrefix*' ORDER BY rowid DESC")
    fun savedBatches(): Flow<List<SavedBatch>>
    @Query("UPDATE AgendaBatch SET saved = 1, linked = 1, items = :items WHERE id = :id")
    suspend fun completeBatch(id: String, items: String)
    @Query("UPDATE AgendaBatch SET items = :items WHERE id = :id AND saved = 0")
    suspend fun revise(id: String, items: String): Int
    @Query("DELETE FROM AgendaBatch WHERE id = :id AND saved = 0") suspend fun discard(id: String)

    @Query("SELECT * FROM AssistantProfile ORDER BY id") fun agents(): Flow<List<AssistantProfile>>
    @Query("SELECT * FROM AssistantProfile WHERE id = :id") suspend fun agent(id: Long): AssistantProfile?
    @Insert suspend fun add(agent: AssistantProfile): Long
    @Update suspend fun update(agent: AssistantProfile)
    // 删除时由同一个事务调用。已确认的批次只解绑助手，保留筛选和日历记录。
    @Delete suspend fun delete(agent: AssistantProfile)
    @Query("DELETE FROM AssistantMessage WHERE agentId = :id") suspend fun clearMessages(id: Long)
    @Query("DELETE FROM AssistantMemory WHERE agentId = :id") suspend fun clearMemory(id: Long)
    @Query("DELETE FROM AgendaBatch WHERE agentId = :id AND saved = 0") suspend fun clearDrafts(id: Long)
    @Query("UPDATE AgendaBatch SET agentId = 0 WHERE agentId = :id AND saved = 1") suspend fun detachBatches(id: Long)
    @Query("DELETE FROM McpEndpoint WHERE agentId = :id") suspend fun clearConnections(id: Long)
    @Query("DELETE FROM AssistantJob WHERE agentId = :id") suspend fun clearJobs(id: Long)
    @Query("DELETE FROM JobRun WHERE agentId = :id") suspend fun clearRuns(id: Long)
    @Query("SELECT id FROM AssistantJob WHERE agentId = :id") suspend fun jobIds(id: Long): List<Long>
    @Query("SELECT * FROM JobRun WHERE agentId = :id") suspend fun runRecords(id: Long): List<JobRun>
    @Query("SELECT * FROM AssistantMessage WHERE agentId = :id ORDER BY id") fun messages(id: Long): Flow<List<AssistantMessage>>
    @Query("SELECT * FROM AssistantMessage WHERE agentId = :id ORDER BY id DESC LIMIT 24")
    suspend fun recent(id: Long): List<AssistantMessage>
    @Insert suspend fun add(message: AssistantMessage)
    @Query("SELECT * FROM AssistantMemory WHERE agentId = :id ORDER BY id") fun memory(id: Long): Flow<List<AssistantMemory>>
    @Query("SELECT * FROM AssistantMemory WHERE agentId = :id ORDER BY id") suspend fun recall(id: Long): List<AssistantMemory>
    @Insert suspend fun add(memory: AssistantMemory)
    @Update suspend fun update(memory: AssistantMemory)
    @Delete suspend fun delete(memory: AssistantMemory)
    @Query("SELECT * FROM McpEndpoint WHERE agentId = :id ORDER BY name") fun servers(id: Long): Flow<List<McpEndpoint>>
    @Query("SELECT * FROM McpEndpoint WHERE agentId = :id ORDER BY name") suspend fun connections(id: Long): List<McpEndpoint>
    @Upsert suspend fun save(server: McpEndpoint)
    @Delete suspend fun delete(server: McpEndpoint)

    @Query("SELECT * FROM AssistantJob ORDER BY id DESC") fun jobs(): Flow<List<AssistantJob>>
    @Query("SELECT * FROM AssistantJob WHERE enabled = 1") suspend fun enabledJobs(): List<AssistantJob>
    @Query("SELECT * FROM AssistantJob WHERE id = :id") suspend fun job(id: Long): AssistantJob?
    @Insert suspend fun add(job: AssistantJob): Long
    @Update suspend fun update(job: AssistantJob)
    @Delete suspend fun delete(job: AssistantJob)
    @Query("SELECT * FROM JobRun WHERE agentId = :id ORDER BY slot DESC") fun runs(id: Long): Flow<List<JobRun>>
    @Query("SELECT * FROM JobRun WHERE jobId = :id AND slot = :slot") suspend fun run(id: Long, slot: String): JobRun?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claim(run: JobRun): Long
    @Update suspend fun update(run: JobRun)
    @Query("SELECT * FROM JobRun WHERE status = '运行中'")
    suspend fun activeRuns(): List<JobRun>
    @Query("UPDATE JobRun SET status = '运行中断', result = '任务已中断，请按需重新安排' WHERE status = '运行中' AND workerId = :worker")
    suspend fun interruptWorker(worker: String): Int
    @Query("UPDATE JobRun SET status = '运行中断', result = '任务已中断，请按需重新安排' WHERE status = '运行中' AND workerId IS NULL")
    suspend fun interruptLegacyRuns()
}

class Workspace(context: Context) {
    private val app = context.applicationContext
    private val db = Store.database(app)
    val dao = db.workspace()
    private val cleanup = app.getSharedPreferences("assistant_cleanup", Context.MODE_PRIVATE)

    // 先持久化令牌与任务的清理凭据，再提交 Room 事务。进程中途退出时，可按助手是否仍存在判断事务结果。
    suspend fun deleteAssistant(id: Long) = cleanupLock.withLock {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val agent = dao.agent(id) ?: return@withTransaction
                val record = JSONObject()
                    .put("tokens", JSONArray(dao.connections(id).map { it.id }))
                    .put("jobs", JSONArray(dao.jobIds(id)))
                    .put("notifications", JSONArray(dao.runRecords(id).map { jobNoticeId(it) }))
                check(cleanup.edit().putString(id.toString(), record.toString()).commit()) { "无法保存清理记录" }
                dao.clearMessages(id)
                dao.clearMemory(id)
                dao.clearDrafts(id)
                dao.detachBatches(id)
                dao.clearConnections(id)
                dao.clearJobs(id)
                dao.clearRuns(id)
                dao.delete(agent)
            }
            finishCleanup(id)
        }
    }

    // 与删除使用同一把锁。失败的外部清理记录保留下来，下次启动继续重试。
    suspend fun resumeAssistantCleanup() = cleanupLock.withLock {
        withContext(Dispatchers.IO) {
            cleanup.all.keys.mapNotNull(String::toLongOrNull).forEach { finishCleanup(it) }
        }
    }

    private suspend fun finishCleanup(id: Long) {
        val key = id.toString()
        val record = cleanup.getString(key, null)?.let(::JSONObject) ?: return
        try {
            if (dao.agent(id) == null) {
                val jobs = AssistantJobs(app)
                val jobIds = record.getJSONArray("jobs")
                for (index in 0 until jobIds.length()) jobs.cancelAndWait(jobIds.getLong(index))
                val tokens = record.getJSONArray("tokens")
                val vault = Vault(app)
                for (index in 0 until tokens.length()) vault.putToken("mcp-${tokens.getString(index)}", "")
                val notices = record.getJSONArray("notifications")
                val notifications = app.getSystemService(android.app.NotificationManager::class.java)
                for (index in 0 until notices.length()) notifications.cancel(notices.getInt(index))
                check(app.getSharedPreferences("assistant_input", Context.MODE_PRIVATE)
                    .edit().remove("input-$id").remove("plan-$id").commit())
            }
            // 令牌与草稿已落盘，清理凭据可以异步移除。若进程此时退出，重复清理也安全。
            cleanup.edit { remove(key) }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.w("Stepwise", "助手清理待重试", error)
        }
    }

    // 原子保存整批事项。即使快速点两次或从后台重新打开草稿，已确认的批次也只写一次。
    suspend fun accept(id: String, rows: List<CalendarEntry>) = db.withTransaction {
        val batch = dao.batch(id) ?: return@withTransaction
        if (!batch.saved) {
            require(rows.isNotEmpty() && rows.all { CalendarWire.valid(it) }) { "请检查日程日期和时间" }
            val source = id.takeUnless { it.startsWith(manualDraftPrefix) }
            dao.addEntries(rows.map { it.copy(batchId = source) })
            dao.completeBatch(id, CalendarWire.encode(rows))
        }
    }

    // 先核对确认时展示的快照，再整批删除。数据在核对后若有变化，整笔操作回滚。
    suspend fun deleteReviewed(rows: List<CalendarEntry>): Int = db.withTransaction {
        val reviewed = rows.associateBy { it.id }
        require(reviewed.isNotEmpty() && reviewed.keys.all { it > 0 })
        val groups = reviewed.keys.chunked(500)
        for (ids in groups) {
            val current = dao.entriesByIds(ids)
            check(current.size == ids.size && current.all { reviewed[it.id] == it }) {
                "部分日程已变更，请重新核对后再删除"
            }
        }
        groups.sumOf { dao.deleteEntries(it) }
    }

    // 网络请求可能跨越删除操作。检查与写入放在同一事务，防止助手删掉后结果重新出现。
    suspend fun saveExchange(agentId: Long, user: String, reply: String) = db.withTransaction {
        require(dao.agent(agentId) != null) { "智能体已删除" }
        dao.add(AssistantMessage(agentId = agentId, role = "user", text = user))
        dao.add(AssistantMessage(agentId = agentId, role = "assistant", text = reply))
    }

    suspend fun saveDraft(batch: AgendaBatch) = db.withTransaction {
        require(batch.agentId == plannerAgentId || dao.agent(batch.agentId) != null) { "智能体已删除" }
        dao.add(batch)
    }

    suspend fun saveMemories(agentId: Long, notes: List<String>) = db.withTransaction {
        require(dao.agent(agentId) != null) { "智能体已删除" }
        notes.forEach { dao.add(AssistantMemory(agentId = agentId, text = it)) }
    }

    // 后台工作只在当前任务仍有效时领取或发布。发布与删除共享锁，旧通知不会晚于删除出现。
    suspend fun current(job: AssistantJob): Boolean =
        dao.job(job.id)?.let { it.enabled && it.revision == job.revision && dao.agent(it.agentId) != null } == true

    suspend fun claim(job: AssistantJob, run: JobRun): Boolean = db.withTransaction {
        current(job) && dao.claim(run) != -1L
    }

    suspend fun finish(job: AssistantJob, run: JobRun, batch: AgendaBatch?): Boolean = db.withTransaction {
        if (!current(job) || dao.run(run.jobId, run.slot)?.workerId != run.workerId) return@withTransaction false
        if (batch != null) dao.add(batch)
        dao.update(run)
        true
    }

    suspend fun whileActive(job: AssistantJob, action: () -> Unit) = cleanupLock.withLock {
        if (current(job)) action()
    }

    private companion object {
        val cleanupLock = Mutex()
    }
}

// 模型和编辑器共用 ISO 日期与分钟格式。end 晚于 start 才能生成定时日程。
object CalendarWire {
    // 编辑器和批量筛选使用同一组类型编码与中文标签。
    internal val kinds = mapOf("event" to "普通", "assignment" to "作业", "course" to "课程")

    // 为手动新建准备一小时的草稿。今天从当前整点开始，其他日期从上午九点开始；夜间允许跨日。
    fun newEvent(day: Long, now: LocalDateTime = LocalDateTime.now()): CalendarEntry {
        val date = LocalDate.ofEpochDay(day)
        val hour = if (date == now.toLocalDate()) now.hour else 9
        val start = minute(date.atTime(hour, 0))
        return CalendarEntry(title = "", day = day, start = start, end = start + 60)
    }

    fun minute(value: LocalDateTime): Long =
        value.toLocalDate().toEpochDay() * 1440 + value.hour * 60 + value.minute
    fun time(minute: Long): LocalDateTime = LocalDate.ofEpochDay(Math.floorDiv(minute, 1440L))
        .atStartOfDay().plusMinutes(Math.floorMod(minute, 1440L))
    fun valid(entry: CalendarEntry): Boolean = entry.title.isNotBlank() && entry.kind in kinds &&
        (entry.start == null && entry.end == null ||
            entry.start != null && entry.end != null && entry.end > entry.start &&
            entry.end - entry.start <= 7 * 1440 && Math.floorDiv(entry.start, 1440L) == entry.day)

    // AI 初次生成须有事项；本地编辑允许移除最后一项，保留空草稿供确认丢弃。
    fun parse(json: String, allowEmpty: Boolean = false): List<CalendarEntry> {
        val items = JSONObject(json).getJSONArray("items")
        require(items.length() in (if (allowEmpty) 0 else 1)..120) { "日程草稿为空或条数过多" }
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            edit(item.getString("title"), item.getString("date"),
                if (item.isNull("start")) "" else item.getString("start"),
                if (item.isNull("end")) "" else item.getString("end"),
                if (item.isNull("place")) "" else item.getString("place"), item.optString("type", "event"), if (item.isNull("note")) "" else item.getString("note"))
        }
    }

    fun edit(title: String, date: String, start: String, end: String, place: String, type: String, note: String = ""): CalendarEntry {
        val day = LocalDate.parse(date.trim()).toEpochDay()
        val row = CalendarEntry(title = title.trim(), day = day,
            start = start.trim().takeIf(String::isNotEmpty)?.let { minute(LocalDateTime.parse(it)) },
            end = end.trim().takeIf(String::isNotEmpty)?.let { minute(LocalDateTime.parse(it)) },
            place = place.trim(), kind = type, note = note.trim())
        require(valid(row)) { "请填写标题。开始日期须一致，结束须晚于开始且在七天内" }
        return row
    }

    fun encode(items: List<CalendarEntry>): String = JSONObject().put("items", JSONArray().apply {
        items.forEach { row ->
            put(JSONObject().put("title", row.title).put("date", LocalDate.ofEpochDay(row.day).toString())
                .put("start", row.start?.let { time(it).toString() } ?: JSONObject.NULL)
                .put("end", row.end?.let { time(it).toString() } ?: JSONObject.NULL)
                .put("place", row.place).put("type", row.kind).put("note", row.note))
        }
    }).toString()
}

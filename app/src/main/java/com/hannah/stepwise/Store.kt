package com.hannah.stepwise

import android.content.Context
import androidx.room.*
import java.time.LocalDate

// 目标、课程步骤与习惯完成记录。日期按本地日历保存为距 1970-01-01 的天数。
@Entity
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val habit: Boolean,
    val start: Long,
    val weekdays: Int = 127,
    val daily: Int = 1,
    val deadline: Long? = null,
    // 原来的习惯和课程均默认不是独立倒计时，旧数据仍按各自类型读取。
    @ColumnInfo(defaultValue = "0") val countdown: Boolean = false
) {
    // 只有普通课程目标需要生成课节和补排。
    val isStudyPlan: Boolean get() = !habit && !countdown
}

@Entity(
    foreignKeys = [ForeignKey(entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("goalId")]
)
data class Lesson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val position: Int,
    val title: String,
    val action: String,
    val minutes: Int?,
    val source: String,
    val scheduled: Long,
    val completed: Long? = null
)

@Entity(
    primaryKeys = ["goalId", "day"],
    foreignKeys = [ForeignKey(entity = Goal::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.CASCADE)]
)
data class HabitLog(
    val goalId: Long, val day: Long,
    // 保留取消过的日期，历史页仍可恢复。这些行不计入打卡天数。
    @ColumnInfo(defaultValue = "0") val cancelled: Boolean = false
)

data class Draft(val title: String, val action: String = "", val minutes: Int? = null, val source: String = "")
data class Row(val goal: Goal, val day: Long, val lesson: Lesson?, val done: Boolean)
data class Snapshot(val day: Long, val goals: List<Goal>, val lessons: List<Lesson>, val habits: List<HabitLog>) {
    // 当天和未来按安排展示，历史按实际完成日期展示。
    fun rows(date: Long): List<Row> = goals.flatMap { goal ->
        if (goal.habit) {
            val log = habits.firstOrNull { it.goalId == goal.id && it.day == date }
            if (log != null || date >= day && date >= goal.start && Planner.allowed(date, goal.weekdays))
                listOf(Row(goal, date, null, log?.cancelled == false))
            else emptyList()
        } else if (goal.isStudyPlan) {
            lessons.filter { it.goalId == goal.id && (it.completed ?: it.scheduled) == date }
                .map { Row(goal, date, it, it.completed != null) }
        } else emptyList()
    }

    // 独立倒计时只在截止日期出现。它没有完成框，也不计入每日待办提醒。
    fun countdownsOn(date: Long): List<Goal> = goals.filter { it.countdown && it.deadline == date }

    // 日程日期来自真实记录和截止日，并预览未来两周的习惯。
    fun agenda(): List<Long> = (lessons.map { it.completed ?: it.scheduled } +
        goals.filter { it.countdown }.mapNotNull { it.deadline } + habits.map { it.day } + (day..day + 13).filter { date -> rows(date).any { it.goal.habit } } + day)
        .distinct().sorted()
}

@Dao
interface PlanDao {
    // 按稳定顺序读写记录。打卡表的复合主键保证每目标每天只有一条。
    @Query("SELECT * FROM Goal ORDER BY id") suspend fun goals(): List<Goal>
    @Query("SELECT * FROM Goal WHERE id = :id") suspend fun goal(id: Long): Goal?
    @Query("SELECT * FROM Lesson ORDER BY goalId, position") suspend fun lessons(): List<Lesson>
    @Query("SELECT * FROM HabitLog ORDER BY day") suspend fun habits(): List<HabitLog>
    @Query("SELECT * FROM Lesson WHERE goalId = :goalId ORDER BY position")
    suspend fun lessonsFor(goalId: Long): List<Lesson>
    @Insert suspend fun add(goal: Goal): Long
    @Insert suspend fun add(lessons: List<Lesson>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun check(log: HabitLog)
    // 只更新已经存在的行。补勾历史必须来自曾经打过卡的那一天。
    @Query("UPDATE HabitLog SET cancelled = :cancelled WHERE goalId = :id AND day = :day")
    suspend fun setCancelled(id: Long, day: Long, cancelled: Boolean)
    @Update suspend fun update(goal: Goal)
    @Update suspend fun update(lessons: List<Lesson>)
    @Delete suspend fun delete(goal: Goal)
    @Delete suspend fun delete(lesson: Lesson)
}

// Room 依据各版架构增加新表或字段，保留原先的课程、习惯和打卡日期。
@Database(entities = [Goal::class, Lesson::class, HabitLog::class, CalendarEntry::class,
    AgendaBatch::class, AssistantProfile::class, AssistantMessage::class, AssistantMemory::class,
    McpEndpoint::class, AssistantJob::class, JobRun::class, AgentSkill::class, SkillAssignment::class], version = 6,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)])
abstract class Plans : RoomDatabase() {
    abstract fun dao(): PlanDao
    abstract fun workspace(): WorkspaceDao
    abstract fun skills(): SkillsDao
}

// 共用同一个数据库实例。每次保存或补排都在事务内执行，避免读取到更新一半的数据。
class Store(context: Context) {
    private val db = database(context)
    private val dao = db.dao()

    private suspend fun schedule(goal: Goal, today: Long, force: Boolean) {
        val all = dao.lessonsFor(goal.id)
        val pending = all.filter { it.completed == null }
        if (pending.isEmpty() || !force && pending.first().scheduled >= today) return
        val start = maxOf(today, goal.start)
        val todayCount = if (start == today) all.count { it.completed == today } else 0
        val dates = Planner.dates(start, goal.weekdays, goal.daily, pending.size, todayCount)
        dao.update(pending.zip(dates).map { (lesson, date) -> lesson.copy(scheduled = date) })
    }

    suspend fun snapshot(today: Long): Snapshot = db.withTransaction {
        // 一次性待办保留记录上的日期，跨日时只有自学计划重新排课。
        val goals = dao.goals()
        goals.filter { it.isStudyPlan }.forEach { schedule(it, today, false) }
        Snapshot(today, goals, dao.lessons(), dao.habits())
    }

    suspend fun save(goal: Goal, newLessons: List<Draft>, today: Long) = db.withTransaction {
        // 独立倒计时必须有截止日期，课节只属于自学计划。
        require(!goal.countdown || !goal.habit && goal.deadline != null)
        require(goal.isStudyPlan || newLessons.isEmpty())
        val previous = if (goal.id == 0L) null else dao.goal(goal.id)
        val id = if (goal.id == 0L) dao.add(goal) else goal.id.also { dao.update(goal) }
        if (newLessons.isNotEmpty()) {
            val position = (dao.lessonsFor(id).maxOfOrNull { it.position } ?: -1) + 1
            dao.add(newLessons.mapIndexed { index, item ->
                Lesson(0, id, position + index, item.title, item.action, item.minutes, item.source, goal.start)
            })
        }
        // 截止日和标题的调整不改变课程日期，只有学期安排或新课改变时才重排。
        if (goal.isStudyPlan && (newLessons.isNotEmpty() || previous == null ||
            goal.start != previous.start || goal.weekdays != previous.weekdays || goal.daily != previous.daily))
            schedule(goal.copy(id = id), today, true)
    }

    suspend fun toggle(row: Row, today: Long) = db.withTransaction {
        val lesson = row.lesson
        if (lesson == null) {
            if (!row.done && row.day == today) dao.check(HabitLog(row.goal.id, row.day))
            if (row.day <= today) dao.setCancelled(row.goal.id, row.day, row.done)
        } else if (row.done || row.day == today) {
            val latest = dao.lessonsFor(row.goal.id).firstOrNull { it.id == lesson.id } ?: return@withTransaction
            if ((latest.completed != null) != row.done) return@withTransaction
            dao.update(listOf(latest.copy(completed = if (row.done) null else today)))
            if (row.done) schedule(row.goal, today, true)
        }
    }

    // 旧版已经删除的历史打卡由用户指定原日期。写入和恢复标记同属一次事务。
    suspend fun restoreHabit(goalId: Long, day: Long, today: Long) = db.withTransaction {
        val goal = dao.goal(goalId)
        require(goal != null && goal.habit && day in goal.start until today)
        dao.check(HabitLog(goalId, day))
        dao.setCancelled(goalId, day, false)
    }

    // 单节课程沿用原编号和完成状态；删除未完成课节时立即重排后续课程。
    suspend fun editLesson(lesson: Lesson, draft: Draft) = db.withTransaction {
        val current = dao.lessonsFor(lesson.goalId).firstOrNull { it.id == lesson.id } ?: return@withTransaction
        dao.update(listOf(current.copy(title = draft.title, action = draft.action, minutes = draft.minutes, source = draft.source)))
    }

    suspend fun deleteLesson(goal: Goal, lesson: Lesson, day: Long) = db.withTransaction {
        dao.delete(lesson)
        schedule(goal, day, true)
    }

    suspend fun delete(goal: Goal) = db.withTransaction { dao.delete(goal) }

    companion object {
        @Volatile private var instance: Plans? = null
        internal fun database(context: Context): Plans = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, Plans::class.java, "stepwise.db")
                .build().also { instance = it }
        }
    }
}

fun today(): Long = LocalDate.now().toEpochDay()

// 已完成的课程优先展示结果。其他目标以本地日历日计算剩余或逾期天数。
internal fun deadlineLabel(deadline: Long, current: Long, complete: Boolean): String = when {
    complete -> "已完成"
    deadline > current -> "还剩${deadline - current}天"
    deadline == current -> "今天截止"
    else -> "已逾期${current - deadline}天"
}

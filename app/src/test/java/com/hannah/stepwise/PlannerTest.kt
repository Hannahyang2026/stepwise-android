package com.hannah.stepwise

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlannerTest {
    // 以周一、三、五为例检查跨周补排和当天名额。
    private val mask = 1 or 4 or 16
    private fun day(iso: String) = LocalDate.parse(iso).toEpochDay()

    @Test fun missedWednesdayMovesToFriday() {
        assertEquals(listOf(day("2026-11-27"), day("2026-11-30"), day("2026-12-02")),
            Planner.dates(day("2026-11-27"), mask, 1, 3, 0))
    }

    @Test fun doneTodayConsumesOneSlot() {
        assertEquals(listOf(day("2026-11-27"), day("2026-11-30"), day("2026-11-30")),
            Planner.dates(day("2026-11-27"), mask, 2, 3, 1))
    }

    @Test fun offDaySkipsToNextStudyDay() {
        assertEquals(listOf(day("2026-11-30")), Planner.dates(day("2026-11-28"), mask, 1, 1, 0))
    }

    // 两个目标在同一天各自显示，习惯只从当天开始展示未打卡项。
    @Test fun goalsShareTodayButHabitsDoNotCarryOver() {
        val today = day("2026-11-27")
        val goal = Goal(1, "课程", false, today - 7)
        val habit = Goal(2, "读论文", true, today - 7)
        val lesson = Lesson(1, 1, 0, "FASTQ", "识别格式", 15, "FASTQ", today)
        val empty = Snapshot(today, listOf(goal, habit), listOf(lesson), emptyList())
        assertEquals(2, empty.rows(today).size)
        assertTrue(empty.rows(today - 1).isEmpty())
        val checked = empty.copy(habits = listOf(HabitLog(2, today), HabitLog(2, today - 1)))
        assertTrue(checked.rows(today - 1).single().done)
        assertEquals(1, checked.rows(today).count { it.done })
    }

    // 独立倒计时只占截止日，不生成待办；课程的可选截止日期不产生额外日程。
    @Test fun standaloneCountdownAppearsOnlyOnItsDeadline() {
        val today = day("2026-11-25")
        val expired = Goal(1, "已截止", false, today - 10, deadline = today - 1, countdown = true)
        val upcoming = Goal(2, "准备考试", false, today, deadline = today + 18, countdown = true)
        val course = Goal(3, "课程", false, today, deadline = today + 8)
        val snapshot = Snapshot(today, listOf(expired, upcoming, course), emptyList(), emptyList())
        assertEquals(listOf(today - 1, today, today + 18), snapshot.agenda())
        assertEquals(listOf(upcoming), snapshot.countdownsOn(today + 18))
        assertEquals(listOf(expired), snapshot.countdownsOn(today - 1))
        assertTrue(snapshot.countdownsOn(today).isEmpty())
        assertTrue(snapshot.rows(today + 18).isEmpty())
    }
}

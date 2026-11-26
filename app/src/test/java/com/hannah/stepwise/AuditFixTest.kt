package com.hannah.stepwise

import org.junit.Assert.*
import org.junit.Test

class AuditFixTest {
    // 历史打卡取消后仍维持日期索引，复选框可重新勾选。
    @Test fun cancelledHabitKeepsHistoryRow() {
        val habit = Goal(id = 1, title = "阅读", habit = true, start = 10)
        val cancelled = HabitLog(habit.id, day = 11, cancelled = true)
        val history = Snapshot(12, listOf(habit), emptyList(), listOf(cancelled))
        assertTrue(11L in history.agenda())
        assertFalse(history.rows(11).single().done)
        assertTrue(history.copy(habits = listOf(cancelled.copy(cancelled = false))).rows(11).single().done)
    }

    // 相邻的五分钟任务显示在不同列，但每条的实际起止分钟照原值保留。
    @Test fun shortLabelsDoNotOverlap() {
        val first = CalendarEntry(id = 1, title = "拉伸", day = 0, start = 60, end = 65)
        val next = first.copy(id = 2, title = "休息", start = 65, end = 70)
        val labels = placeEvents(listOf(first, next), 0, eventLabelMinutes)
        assertEquals(listOf(0, 1), labels.map { it.column })
        assertEquals(listOf(65, 70), labels.map { it.to })
    }

    // 最后一项可从本地草稿移除，空的模型回复仍由生成流程拒绝。
    @Test fun emptyEditedBatchCanReopen() {
        val raw = CalendarWire.encode(emptyList())
        assertTrue(CalendarWire.parse(raw, allowEmpty = true).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { CalendarWire.parse(raw) }
    }
}

package com.hannah.stepwise

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class Release21Test {
    // 手动创建的日程应沿用选中日并能跨午夜；零点之后的部分由第二天的时间轴显示。
    @Test fun manualEventUsesSelectedDayAndSpansMidnight() {
        val now = java.time.LocalDateTime.parse("2026-11-30T23:45")
        val day = now.toLocalDate().toEpochDay()
        val event = CalendarWire.newEvent(day, now).copy(title = "学习")
        assertTrue(CalendarWire.valid(event))
        val end = requireNotNull(event.end)
        assertEquals("2026-12-01T00:00", CalendarWire.time(end).toString())
        val late = event.copy(end = end + 30)
        assertEquals(30, placeEvents(listOf(late), day + 1).single().to)
        val tomorrow = CalendarWire.newEvent(day + 1, now)
        assertEquals("2026-12-01T09:00", CalendarWire.time(tomorrow.start!!).toString())
    }

    // 导入多行 YAML 描述与 Markdown 正文，确认格式错误时不会生成待安装对象。
    @Test fun skillImportReadsCanonicalMetadata() {
        val markdown = """
            ---
            name: learning-review
            description: >
              Prepare a study review
              with a short exercise.
            metadata:
              author: example
            ---
            # Review
            Use a quiz.
        """.trimIndent()
        val skill = SkillImport.fromMarkdown(markdown, "SKILL.md")
        assertEquals("learning-review", skill.name)
        assertEquals("Prepare a study review with a short exercise.", skill.description.trim())
        assertEquals("# Review\nUse a quiz.", skill.body)
        assertEquals("SKILL.md", skill.source)
        assertTrue(runCatching { SkillImport.fromMarkdown("<html>error</html>", "sample") }.isFailure)
        assertTrue(runCatching { SkillImport.fromMarkdown(markdown.replace("learning-review", "Wrong Name"), "sample") }.isFailure)
    }

    // 新草稿保存备注；已有 2.0 草稿没有备注字段时仍能读取。
    @Test fun calendarNotesSurviveRoundTripAndOldDraftsLoad() {
        val legacy = """{"items":[{"title":"Review","date":"2026-11-24","start":null,"end":null}]}"""
        val old = CalendarWire.parse(legacy).single()
        assertEquals("", old.note)
        val marked = old.copy(note = "Record QC steps")
        assertEquals(listOf(marked), CalendarWire.parse(CalendarWire.encode(listOf(marked))))
    }

    // 目标完成优先于截止状态，预约时间到点才进入提醒判断。
    @Test fun deadlineAndReminderBoundaries() {
        val date = LocalDate.parse("2026-11-24").toEpochDay()
        assertEquals("还剩2天", deadlineLabel(date + 2, date, false))
        assertEquals("今天截止", deadlineLabel(date, date, false))
        assertEquals("已逾期1天", deadlineLabel(date - 1, date, false))
        assertEquals("已完成", deadlineLabel(date - 1, date, true))
        val reminder = Reminder(true, 19, 0)
        assertFalse(reminderDue(reminder, LocalTime.parse("18:59:59")))
        assertTrue(reminderDue(reminder, LocalTime.parse("19:00")))
        assertFalse(reminderDue(reminder.copy(enabled = false), LocalTime.parse("21:00")))
    }
}

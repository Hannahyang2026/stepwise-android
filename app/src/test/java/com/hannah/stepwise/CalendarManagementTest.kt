package com.hannah.stepwise

import org.junit.Assert.*
import org.junit.Test

class CalendarManagementTest {
    // 组合筛选同时检查类型、标题或备注，以及准确记录的新批次。
    @Test fun filtersCombineAndPreserveExactBatch() {
        val course = CalendarEntry(id = 1, title = "RNA 课程", day = 1, kind = "course", batchId = "a")
        val note = course.copy(id = 2, title = "复习", note = "rna 脚本")
        val different = course.copy(id = 3, batchId = "b")
        val manual = course.copy(id = 4, batchId = null)
        val assignment = course.copy(id = 5, kind = "assignment")
        val batch = SavedBatch(1, "a", "", true)
        assertEquals(listOf(1L, 2L), filterCalendar(listOf(course, note, different, manual, assignment),
            "course", " Rna ", batch, emptyList()).map { it.id })
    }

    // 旧批次只能列出与草稿内容一致的未关联事项。新版的已知来源不能被误归到旧批次。
    @Test fun legacyCandidatesNeverIncludeKnownNewBatch() {
        val draft = CalendarEntry(title = "测试", day = 2)
        val candidate = draft.copy(id = 1, done = true)
        val moved = draft.copy(id = 2, day = 3)
        val linked = draft.copy(id = 3, batchId = "current")
        val batch = SavedBatch(1, "old", "", false)
        assertEquals(listOf(candidate), filterCalendar(listOf(candidate, moved, linked),
            null, "", batch, listOf(draft)))
    }
}

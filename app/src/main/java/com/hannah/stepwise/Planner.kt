package com.hannah.stepwise

import java.time.LocalDate

// 纯排程逻辑。输入星期位图（周一为第 0 位）、每天课数与起始日，输出每课日期。
object Planner {
    fun allowed(day: Long, weekdays: Int): Boolean =
        weekdays and (1 shl (LocalDate.ofEpochDay(day).dayOfWeek.value - 1)) != 0

    fun dates(first: Long, weekdays: Int, daily: Int, count: Int, doneToday: Int): List<Long> {
        require(weekdays in 1..127 && daily > 0)
        var day = first
        var occupied = doneToday
        return List(count) {
            // 当天已完成的课也占用名额，避免补排把今天排满两次。
            while (!allowed(day, weekdays) || occupied >= daily) {
                day++
                occupied = 0
            }
            occupied++
            day
        }
    }
}

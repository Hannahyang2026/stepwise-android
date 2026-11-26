package com.hannah.stepwise

import android.app.DatePickerDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs

// 所有卡片按可用字号计算同一高度。截止状态可以换一行，进度条占用预留空间。
@Composable
internal fun GoalCards(data: Snapshot, create: () -> Unit, edit: (Goal) -> Unit) {
    val typography = MaterialTheme.typography
    val itemStyle = stepItemTitle
    // 数字比页面标题大一档，sp 会随系统字号缩放。卡片按同一高度预留换行和日期的空间。
    val numberStyle = typography.titleLarge.copy(fontSize = (typography.titleLarge.fontSize.value + 4).sp)
    val noteStyle = typography.bodySmall
    val measure = rememberTextMeasurer()
    val cardHeight = with(LocalDensity.current) {
        // 实测两种文本的像素高度。系统大字号会放大字体的上下空间，跨年日期的第二行也要计入。
        val statusSample = buildAnnotatedString {
            if (fontScale >= 1.4f) append("还剩\n")
            val start = length
            append("418")
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = numberStyle.fontSize), start, length)
            append(" 天")
        }
        val statusHeight = measure.measure(statusSample, style = noteStyle.copy(lineHeight = numberStyle.lineHeight)).size.height
        val dateHeight = measure.measure("2028年\n12月31日", style = noteStyle).size.height
        // 三类卡共用更低的下限，仍给放大字号或跨年日期留足文字高度。
        maxOf(88.dp, (statusHeight + dateHeight).toDp() + 22.dp)
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.goals.isEmpty()) item { Text("还没有目标", Modifier.padding(top = 4.dp)) }
            items(data.goals, key = { it.id }) { goal ->
                // 课程进度和习惯打卡仍从当前快照计算，倒计时直接取截止日。
                val lessons = data.lessons.filter { it.goalId == goal.id }
                val done = lessons.count { it.completed != null }
                val complete = goal.isStudyPlan && lessons.isNotEmpty() && done == lessons.size
                val progress = if (goal.habit) "已打卡 ${data.habits.count { it.goalId == goal.id && !it.cancelled }} 天"
                    else "已完成 $done / ${lessons.size} 课"
                Card(onClick = { edit(goal) }, modifier = Modifier.fillMaxWidth().height(cardHeight), shape = stepShape,
                    colors = CardDefaults.cardColors(containerColor = stepCell)) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val gap = 8.dp
                        val available = maxWidth - gap
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(gap)) {
                            // 倒计时取实际文字宽度，标题接收剩余空间。没有截止日时使用整行。
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(goal.title, style = itemStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!goal.countdown) Text(progress, style = typography.bodyMedium, color = stepAccent, maxLines = 2)
                                if (lessons.isNotEmpty()) LinearProgressIndicator(progress = { done.toFloat() / lessons.size },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            goal.deadline?.let { day ->
                                CountdownSummary(day, data.day, complete, numberStyle, available)
                            }
                        }
                    }
                }
            }
        }
        // 新建操作占独立行，列表滚到底后最后一张卡仍可点击。
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
            StepRoundAction(StepGlyph.Add, "新建目标", fill = stepAccent,
                ink = androidx.compose.ui.graphics.Color.White, onClick = create)
        }
    }
}

// 右栏把天数和截止日期向右对齐。用浅灰正文衬托朱红数字，窄窗允许整组数字和单位换行。
@Composable
private fun CountdownSummary(deadline: Long, current: Long, complete: Boolean,
    numberStyle: androidx.compose.ui.text.TextStyle, availableWidth: Dp) {
    val style = MaterialTheme.typography.bodySmall
    val status = deadlineLabel(deadline, current, complete)
    val days = abs(deadline - current).toString()
    val number = status.indexOf(days)
    val emphasized = buildAnnotatedString {
        // 用不可断行空格把数字与「天」连在一起。「今天截止」等无数字状态直接沿用原文。
        if (number < 0) append(status) else {
            append(status.substring(0, number))
            append(' ')
            val start = length
            append(days)
            addStyle(SpanStyle(color = stepRed, fontWeight = FontWeight.Bold, fontSize = numberStyle.fontSize),
                start, length)
            append('\u00A0')
            append(status.substring(number + days.length))
        }
    }
    val date = LocalDate.ofEpochDay(deadline)
    val shortDate = "${date.monthValue}月${date.dayOfMonth}日"
    val year = "${date.year}年".takeIf { date.year != LocalDate.ofEpochDay(current).year }.orEmpty()
    val measure = rememberTextMeasurer()
    val width = with(LocalDensity.current) {
        // 优先控制在 36% 内。数字连单位、跨年年份等完整片段放不下时可扩至 44%。
        val statusWidth = measure.measure(emphasized, style = style).size.width.toDp()
        val dateWidth = measure.measure(year + shortDate, style = style).size.width.toDp()
        val crucial = if (number < 0) emphasized else emphasized.subSequence(number + 1, emphasized.length)
        val minimum = maxOf(measure.measure(crucial, style = style).size.width,
            measure.measure(year.ifEmpty { "${date.monthValue}/${date.dayOfMonth}" }, style = style).size.width).toDp()
        maxOf(minOf(maxOf(statusWidth, dateWidth), availableWidth * .36f), minOf(minimum, availableWidth * .44f))
    }
    BoxWithConstraints(Modifier.width(width)) {
        // 根据日期实际显示宽度换行。跨年的截止日保留年份，极窄时将月日写成数字。
        val available = with(LocalDensity.current) { maxWidth.roundToPx() }
        val monthDay = if (measure.measure(shortDate, style = style).size.width <= available)
            shortDate else "${date.monthValue}/${date.dayOfMonth}"
        val fullDate = year + monthDay
        val dateText = if (year.isNotEmpty() && measure.measure(fullDate, style = style).size.width > available)
            "$year\n$monthDay" else fullDate
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 状态内的数字可放大，文案和日期共用右边界。
            Text(emphasized, Modifier.fillMaxWidth(), style = style.copy(lineHeight = numberStyle.lineHeight),
                color = if (deadline == current && !complete) stepRed else stepMuted, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(dateText, Modifier.fillMaxWidth().semantics { contentDescription = label(deadline) },
                style = style, color = stepMuted, textAlign = TextAlign.End, maxLines = 2)
        }
    }
}

// 独立倒计时只需要名称和日期。保存后交给共用目标存储，修改日期也会更新日程。
@Composable
internal fun CountdownEditor(goal: Goal, close: () -> Unit, save: (Goal) -> Unit, delete: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(goal.title) }
    var deadline by rememberSaveable { mutableStateOf(goal.deadline) }
    var remove by remember { mutableStateOf(false) }
    FormDialog(if (goal.id == 0L) "新建倒计时" else "编辑倒计时", close, actions = {
        if (goal.id != 0L) TextButton(onClick = { remove = true }) { Text("删除", color = stepRed) }
        Button(shape = stepControlShape, enabled = name.isNotBlank() && deadline != null,
            onClick = { save(goal.copy(title = name.trim(), deadline = deadline)) }) { Text("保存") }
    }) {
        Field(name, { name = it }, "倒计时名称")
        TextButton(onClick = {
            val day = LocalDate.ofEpochDay(deadline ?: today())
            DatePickerDialog(context, { _, y, m, d ->
                deadline = LocalDate.of(y, m + 1, d).toEpochDay()
            }, day.year, day.monthValue - 1, day.dayOfMonth).show()
        }) { Text(deadline?.let { "截止日期 · ${label(it)}" } ?: "选择截止日期", fontWeight = FontWeight.Bold) }
    }
    if (remove) AlertDialog(shape = stepDialogShape, onDismissRequest = { remove = false },
        title = { Text("删除倒计时？") }, text = { Text(goal.title) },
        confirmButton = { TextButton(onClick = { remove = false; delete() }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("取消") } })
}

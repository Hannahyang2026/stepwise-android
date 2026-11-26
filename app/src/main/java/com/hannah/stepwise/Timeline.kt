package com.hannah.stepwise

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil

private val calendarGray = Color(0xFF686962)
// 网格比正文的辅助文字浅一级，避免小时线与卡片内容争抢视线。
private val grid = Color(0xFFE6E3DE)
// 短日程的标签至少占半小时高度；真实起止仍写在标签内。
internal const val eventLabelMinutes = 30

// 同一份日程按选中日裁切，跨午夜时只取当天部分。互相重叠的事项共享一组列。
internal data class Placement(val item: CalendarEntry, val from: Int, val to: Int, val column: Int, val columns: Int)
internal fun placeEvents(items: List<CalendarEntry>, day: Long, labelMinutes: Int = 0): List<Placement> {
    val first = day * 1440
    val last = first + 1440
    val blocks = items.mapNotNull { item ->
        val start = item.start ?: return@mapNotNull null
        val end = item.end ?: return@mapNotNull null
        if (start >= last || end <= first) null
        else Triple(item, (maxOf(first, start) - first).toInt(), (minOf(last, end) - first).toInt())
    }.sortedWith(compareBy({ it.second }, { it.third }, { it.first.id }))
    val output = mutableListOf<Placement>()
    val group = mutableListOf<Placement>()
    val lanes = mutableListOf<Int>()
    for ((item, from, to) in blocks) {
        if (lanes.all { it <= from }) {
            output.addAll(group.map { it.copy(columns = lanes.size) })
            group.clear()
            lanes.clear()
        }
        val lane = lanes.indexOfFirst { it <= from }.let { if (it < 0) lanes.size else it }
        // 分列同时考虑标签的实际绘制范围，避免相邻的短日程遮住彼此。
        val labelEnd = maxOf(to, from + labelMinutes)
        if (lane == lanes.size) lanes.add(labelEnd) else lanes[lane] = labelEnd
        group.add(Placement(item, from, to, lane, 1))
    }
    output.addAll(group.map { it.copy(columns = lanes.size) })
    return output
}

internal fun weekDays(date: LocalDate): List<LocalDate> {
    val sunday = date.minusDays((date.dayOfWeek.value % 7).toLong())
    return List(7) { sunday.plusDays(it.toLong()) }
}

internal fun lunarName(date: LocalDate, includeMonth: Boolean = false): String {
    // 中午取值可避开夏令时的午夜切换，按中国农历日期格式显示。
    val zone = ZoneId.of("Asia/Shanghai")
    val calendar = ChineseCalendar(TimeZone.getTimeZone(zone.id))
    calendar.timeInMillis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val number = calendar.get(Calendar.DAY_OF_MONTH)
    val digits = "一二三四五六七八九十"
    val dayName = when {
        number <= 10 -> "初${digits[number - 1]}"
        number < 20 -> "十${digits[number - 11]}"
        number == 20 -> "二十"
        number < 30 -> "廿${digits[number - 21]}"
        else -> "三十"
    }
    // 周条只显示日；日程组头还要显示月名，并保留闰月标记。
    if (!includeMonth) return dayName
    val months = listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    val leap = if (calendar.get(Calendar.IS_LEAP_MONTH) == 1) "闰" else ""
    return "$leap${months[calendar.get(Calendar.MONTH)]}月$dayName"
}

// 月份与周条独立导航。只有日期行下的灰色底被选中，星期与农历保持正常背景。
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeekBar(selectedDay: Long, today: Long, select: (Long) -> Unit) {
    val chosen = LocalDate.ofEpochDay(selectedDay)
    val dates = remember(chosen) { weekDays(chosen) }
    val lunar = remember(dates) { dates.map { lunarName(it) } }
    val pivot = dates.indexOf(chosen)
    val onChange by rememberUpdatedState(select)
    // 文字用系统 sp。根据实际行高留出空间，也允许农历在窄列换行。
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    // 两档窗口字号分别在年月和公历数字上加 2 sp，行高也等量加大。
    val monthStyle = typography.titleLarge.copy(fontSize = (typography.titleLarge.fontSize.value + 2).sp,
        lineHeight = (typography.titleLarge.lineHeight.value + 2).sp)
    val dateStyle = typography.labelLarge.copy(fontSize = (typography.bodySmall.fontSize.value + 2).sp,
        lineHeight = (typography.bodySmall.lineHeight.value + 2).sp, fontFeatureSettings = "tnum")
    val cutoff = with(density) { 24.dp.toPx() }
    val headingHeight = with(density) { maxOf(22.dp, typography.labelSmall.lineHeight.toDp() + 6.dp) }
    val dateHeight = with(density) { maxOf(34.dp, dateStyle.lineHeight.toDp() + 10.dp) }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 月份按钮与页面顶部按钮共用 48 dp 的触控宽度，圆心在同一条竖线上。
            // 将年月拆成完整词组，窄屏可整组换行，避免单独掉下一个「月」。
            FlowRow(Modifier.widthIn(max = (maxWidth - stepIconTouchSize * 2).coerceAtLeast(1.dp)),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                Text("${chosen.year}年", color = stepYear, style = monthStyle, softWrap = false)
                Text("${chosen.monthValue}月", color = stepInk, style = monthStyle, softWrap = false)
            }
            Box(Modifier.align(Alignment.CenterStart)) {
                CircleArrow("上月", faceDiameter = 28.dp) { select(chosen.minusMonths(1).toEpochDay()) }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                CircleArrow("下月", next = true, faceDiameter = 28.dp) { select(chosen.plusMonths(1).toEpochDay()) }
            }
        }
        StepGroup {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 78.dp).pointerInput(selectedDay, cutoff) {
                var drag = 0f
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                    change.consume()
                    drag += amount
                }, onDragEnd = {
                    if (kotlin.math.abs(drag) >= cutoff) onChange(selectedDay + if (drag < 0) 7 else -7)
                })
            }) {
                val part = maxWidth / 7
                // 胶囊的两个圆端与对应日期同心，窄屏缩小正圆直径，行高保持一致。
                val diameter = minOf(dateHeight, part)
                val span = if (pivot == dates.lastIndex) 0.dp else part
                Box(Modifier.offset(x = part * (pivot + .5f) - diameter / 2,
                    y = headingHeight + (dateHeight - diameter) / 2)
                    .width(diameter + span).height(diameter).background(Color(0xFFE2DED4), CircleShape))
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    dates.forEachIndexed { index, date ->
                        val picked = index == pivot
                        val isToday = date.toEpochDay() == today
                        val shade = if (date.monthValue == chosen.monthValue) stepInk else stepMuted
                        Column(Modifier.weight(1f).fillMaxHeight()
                            .semantics {
                                selected = picked
                                contentDescription = "$date，农历${lunar[index]}${if (isToday) "，今天" else ""}"
                            }.clickable { select(date.toEpochDay()) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(headingHeight), contentAlignment = Alignment.Center) {
                                Text("日一二三四五六"[index].toString(), color = stepMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Box(Modifier.height(dateHeight), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(diameter).background(
                                    when { !picked -> Color.Transparent; isToday -> stepRed; else -> stepInk }, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(date.dayOfMonth.toString(), color = if (picked) Color.White else shade,
                                        style = dateStyle,
                                        fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                            Text(lunar[index], color = if (picked && isToday) stepRed else shade,
                                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 1.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// 两天只用一份可滚动网格。每一天内部独立计算重叠列，跨午夜事项分段绘制。
@Composable
internal fun TodayTimeline(data: Snapshot, entries: List<CalendarEntry>, selected: Long, select: (Long) -> Unit,
    details: (Row) -> Unit, toggle: (Row) -> Unit, mark: (CalendarEntry) -> Unit, show: (CalendarEntry) -> Unit,
    newTodo: () -> Unit, newEvent: () -> Unit, editEntry: (CalendarEntry) -> Unit, editRow: (Row) -> Unit,
    reveal: CalendarEntry?, revealed: () -> Unit) {
    val days = remember(selected) { listOf(selected, selected + 1) }
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    // 使用等宽数字，实测粗体时间的宽度后再决定列宽，大字号也能完整显示。
    val clockStyle = typography.bodySmall.copy(fontFeatureSettings = "tnum",
        fontSize = (typography.bodySmall.fontSize.value - 2).sp, lineHeight = (typography.bodySmall.lineHeight.value - 3).sp)
    val currentClockStyle = clockStyle.copy(fontWeight = FontWeight.Bold)
    val ruler = rememberTextMeasurer()
    val clockTextWidth = ruler.measure("00:00", style = currentClockStyle, maxLines = 1).size.width
    val smallLine = with(density) { clockStyle.lineHeight.toDp() }
    val eventTextHeight = with(density) { timelineTitle.lineHeight.toDp() + timelineMetadata.lineHeight.toDp() } + 12.dp
    // 默认每小时 48 dp。放大字号后仍按字形增高，刻度对到整像素。
    val clockHeight = maxOf(22.dp, smallLine + 5.dp)
    val hourHeight = with(density) { maxOf(48.dp, (eventTextHeight - 12.dp) * 1.2f, clockHeight + 10.dp).roundToPx().toDp() }
    val tickWidth = with(density) { maxOf(38.dp, clockTextWidth.toDp() + 8.dp) }
    // 缩短小时行后，仍为短日程留出标题和时间的空间。分列和绘制使用同一个最小高度。
    val labelMinutes = maxOf(eventLabelMinutes, ceil(eventTextHeight / hourHeight * 60f).toInt())
    val gridTop = with(density) { maxOf(14.dp, clockHeight / 2 + 2.dp).roundToPx().toDp() }
    val blocks = remember(entries, selected, labelMinutes) { days.map { placeEvents(entries, it, labelMinutes) } }
    var eventListDay by remember { mutableStateOf<Long?>(null) }
    var minute by remember { mutableIntStateOf(clockMinute()) }
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 窄窗使用整页滚动。网格进入可视区后再显示浮动加号，避免它挡住待办卡。
        var headerHeight by remember { mutableIntStateOf(0) }
        val remaining = maxHeight - with(density) { headerHeight.toDp() }
        val compact = maxWidth < 360.dp || maxHeight < 420.dp || (headerHeight > 0 && remaining < 100.dp)
        val actionClearance = stepIconTouchSize + 8.dp
        val timelineSpace = if (compact) remaining + with(density) { scroll.value.toDp() } else remaining
        val todoLimit = with(density) { maxOf(144.dp, (stepItemTitle.lineHeight.toDp() + 16.dp) * 3) }
        val todoHeight = minOf(todoLimit, maxHeight * .34f)
        Column(Modifier.fillMaxSize()) {
            LaunchedEffect(selected, data.day, compact, hourHeight) {
                if (reveal != null) return@LaunchedEffect
                snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
                val first = if (!compact && data.day in days) (clockMinute() - 65).coerceAtLeast(0) else 0
                scroll.scrollTo(with(density) { (hourHeight * (first / 60f)).roundToPx() })
            }
            // 保存后直接滚到开始时刻，留一小段上方刻度供读者对照。
            LaunchedEffect(reveal, compact, hourHeight) {
                val start = reveal?.start ?: return@LaunchedEffect
                snapshotFlow { scroll.maxValue to headerHeight }.first { (max, header) ->
                    max != Int.MAX_VALUE && (!compact || header > 0)
                }
                val minuteOfDay = Math.floorMod(start, 1440L).toInt()
                val position = with(density) { (hourHeight * ((minuteOfDay - 15).coerceAtLeast(0) / 60f)).roundToPx() }
                scroll.scrollTo(position + if (compact) headerHeight else 0)
                revealed()
            }
            LaunchedEffect(Unit) { while (true) { minute = clockMinute(); delay(5_000) } }
            val content = Modifier.weight(1f).fillMaxWidth()
            Column(if (compact) content.verticalScroll(scroll) else content) {
                Column(Modifier.onSizeChanged { headerHeight = it.height }) {
                    WeekBar(selected, data.day, select)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // 标题以左侧按钮圆心为准。系统字体较大时可左右伸出对齐列。
                        Box(Modifier.width(stepIconTouchSize), contentAlignment = Alignment.Center) {
                            Text("待办", Modifier.wrapContentWidth(unbounded = true), color = stepRed,
                                style = typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Spacer(Modifier.weight(1f))
                        StepAddButton("新增待办", newTodo)
                    }
                    // 待办始终属于今天。周条只切换下面的时间轴，不改变这里的待办日期。
                    TodayTodoSummary(data.rows(data.day), entries.filter { it.start == null && it.day == data.day },
                        todoHeight, toggle, details, editRow, mark, show, editEntry)
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Spacer(Modifier.width(tickWidth))
                        days.forEachIndexed { index, day ->
                            // 密集时也能通过日期列头打开整天事项，并独立查看或编辑每一条。
                            val listAction = if (blocks[index].isEmpty()) Modifier else Modifier
                                .clickable { eventListDay = day }
                                .semantics { contentDescription = "${columnLabel(day, data.day)}日程列表" }
                            Box(Modifier.weight(1f).then(listAction), contentAlignment = Alignment.Center) {
                                Text(columnLabel(day, data.day), color = if (day == data.day) stepRed else stepInk,
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    textDecoration = if (blocks[index].isNotEmpty()) TextDecoration.Underline else null)
                            }
                        }
                    }
                    HorizontalDivider(color = grid)
                }
                Box(if (compact) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    // 底部滚动留白让最后一条日程完整移到悬浮按钮上方。网格线按原有高度绘制。
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = actionClearance)
                        .height(gridTop + hourHeight * 24 + hourHeight / 2 + clockHeight)) {
                        val currentTimeY = if (data.day in days) gridTop + hourHeight * (minute / 60f) else null
                        // 让时间胶囊的中线对准实际分钟，小时文字据此避让。
                        val marker = currentTimeY?.let { (it - clockHeight / 2)..(it + clockHeight / 2) }
                        TimeGutter(hourHeight, gridTop, tickWidth, clockStyle, marker)
                        val usable = (maxWidth - tickWidth).coerceAtLeast(1.dp)
                        val dayWidth = usable / 2
                        val gridWeight = with(density) { 1.toDp() }
                        Box(Modifier.offset(x = tickWidth).width(gridWeight).fillMaxHeight().background(grid))
                        Box(Modifier.offset(x = tickWidth + dayWidth).width(gridWeight).fillMaxHeight().background(grid))
                        blocks.forEachIndexed { index, placements ->
                            placements.forEach { block ->
                                val lane = dayWidth / block.columns
                                EventCard(block.item, Modifier.offset(x = tickWidth + dayWidth * index + lane * block.column,
                                    y = gridTop + hourHeight * (block.from / 60f))
                                    .width(lane).height(hourHeight * (maxOf(labelMinutes, block.to - block.from) / 60f)), show, editEntry)
                            }
                        }
                        if (marker != null) {
                            // 红线绘制在事件层之上，从胶囊右侧中点伸出。
                            Box(Modifier.offset(y = marker.start).fillMaxWidth().height(clockHeight).zIndex(1f).drawBehind {
                                val centerY = size.height / 2
                                val start = tickWidth.roundToPx().toFloat()
                                val middle = (tickWidth + dayWidth).toPx()
                                val firstIsToday = days.first() == data.day
                                drawLine(if (firstIsToday) stepRed else stepRed.copy(alpha = .20f),
                                    Offset(start, centerY), Offset(middle, centerY), strokeWidth = 1.dp.toPx())
                                drawLine(if (firstIsToday) stepRed.copy(alpha = .20f) else stepRed,
                                    Offset(middle, centerY), Offset(size.width, centerY), strokeWidth = 1.dp.toPx())
                            }) {
                                Box(Modifier.width(tickWidth).fillMaxHeight().background(stepRed, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Text(clockLabel(minute), color = Color.White, style = currentClockStyle, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (headerHeight > 0 && timelineSpace >= actionClearance)
            Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 4.dp).zIndex(2f)) {
                StepAddButton("新增日程", newEvent)
            }
    }
    eventListDay?.let { day ->
        val index = days.indexOf(day)
        if (index >= 0) TimelineEventList(day, blocks[index], { eventListDay = null }, show, editEntry)
    }
}

// 今日摘要左边显示还需要完成的项数，右边独立滚动并保留已经勾选的条目。
@Composable
private fun TodayTodoSummary(tasks: List<Row>, todos: List<CalendarEntry>, limit: Dp,
    toggle: (Row) -> Unit, details: (Row) -> Unit, editRow: (Row) -> Unit,
    mark: (CalendarEntry) -> Unit, show: (CalendarEntry) -> Unit, editEntry: (CalendarEntry) -> Unit) {
    val typography = MaterialTheme.typography
    val pending = tasks.count { !it.done } + todos.count { !it.done }
    val measurer = rememberTextMeasurer()
    // 用当前字号实测摘要宽高，避免 200% 系统字号时图标与数字互相覆盖。
    val captionStyle = typography.bodySmall.copy(fontSize = (typography.bodySmall.fontSize.value - 2).sp,
        lineHeight = (typography.bodySmall.lineHeight.value - 2).sp)
    val countStyle = typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
    val caption = measurer.measure("未完成", style = captionStyle, maxLines = 1).size
    val count = measurer.measure(pending.toString(), style = countStyle, maxLines = 1).size
    val (summaryTextWidth, summaryMinHeight) = with(LocalDensity.current) {
        maxOf(caption.width, count.width).toDp() to
            maxOf(104.dp, stepRoundInset + stepRoundDiameter + 8.dp + (caption.height + count.height).toDp() + 12.dp)
    }
    // 卡片左上角以 24 dp 为半径；圆面从 7 dp 开始，圆心沿横竖两个方向都落在 24 dp。
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(stepIconTouchSize / 2), color = stepCell) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = summaryMinHeight)) {
            // 给系统大字号留空间，同时至少把大半张卡片留给标题。
            val summaryWidth = maxOf(56.dp, summaryTextWidth + 16.dp).coerceAtMost(maxWidth * .38f)
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width(summaryWidth))
                // 右列保留统一的触控热区、细分隔线和整行查看／长按的动作。
                Column(Modifier.weight(1f).padding(top = stepRoundInset, bottom = 8.dp).heightIn(max = limit)
                    .verticalScroll(rememberScrollState())) {
                    tasks.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = stepLine)
                        TodoLine(row.lesson?.title ?: row.goal.title, row.done, true,
                            { toggle(row) }, { details(row) }, { editRow(row) })
                    }
                    todos.forEachIndexed { index, item ->
                        if (index > 0 || tasks.isNotEmpty()) HorizontalDivider(color = stepLine)
                        TodoLine(item.title, item.done, true, { mark(item) }, { show(item) }, { editEntry(item) })
                    }
                    if (tasks.isEmpty() && todos.isEmpty()) {
                        Text("暂无待办", Modifier.padding(8.dp), color = stepMuted, style = typography.bodySmall)
                    }
                }
            }
            // 左上图标与圆角同心，数量和说明独立锚在左下角，同左边和下边各留 12 dp。
            Box(Modifier.align(Alignment.TopStart).padding(start = stepRoundInset, top = stepRoundInset)
                .size(stepRoundDiameter).background(stepAccent.copy(alpha = .12f), CircleShape),
                contentAlignment = Alignment.Center) { StepIcon(StepGlyph.Agenda, color = stepAccent) }
            Column(Modifier.align(Alignment.BottomStart).width(summaryWidth).padding(start = 12.dp, bottom = 12.dp)) {
                Text(pending.toString(), style = countStyle, color = Color.Black, maxLines = 1)
                Text("未完成", style = captionStyle, color = stepMuted.copy(alpha = .8f), maxLines = 1)
            }
        }
    }
}

// 日期列表给窄列日程提供足宽的点按入口，点击后沿用原有详情与编辑。
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineEventList(day: Long, events: List<Placement>, close: () -> Unit,
    show: (CalendarEntry) -> Unit, edit: (CalendarEntry) -> Unit) {
    FormDialog("${LocalDate.ofEpochDay(day)} 日程", close, actions = {}) {
        events.forEach { block ->
            val item = block.item
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { close(); show(item) },
                onLongClick = { close(); edit(item) }).padding(vertical = 10.dp)) {
                Text(item.title, style = stepItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(eventTime(item), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = stepLine)
        }
    }
}

private fun columnLabel(day: Long, now: Long): String {
    val date = LocalDate.ofEpochDay(day)
    val relative = when (day) { now -> "今天 "; now + 1 -> "明天 "; else -> "" }
    return "$relative${date.monthValue}/${date.dayOfMonth}"
}

// 待办保持整块点击区，小圆点只改变显示。标题只占一行，点开可读完整标题。
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TodoLine(title: String, done: Boolean, enabled: Boolean, toggle: () -> Unit, show: () -> Unit,
    edit: () -> Unit, compact: Boolean = true) {
    // 正常字号下用紧凑行高。系统把文字放大后，按实际行高增高两个独立的点击区。
    val titleStyle = stepItemTitle
    val textHeight = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    // 近日清单采用 38 dp 行高。系统放大字体时，让行高随文字增加。
    val rowHeight = maxOf(if (compact) 38.dp else 44.dp, textHeight + 8.dp)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(if (compact) 36.dp else agendaMarkerWidth).fillMaxHeight()
            .toggleable(value = done, enabled = enabled, role = Role.Checkbox,
            onValueChange = { toggle() }), contentAlignment = Alignment.Center) {
            StepCheckbox(done, onCheckedChange = null, enabled = enabled, circular = true)
        }
        // 勾选区独立，右侧整行都能查看和长按编辑，包括标题上下的留白。
        Box(Modifier.weight(1f).fillMaxHeight()
            .combinedClickable(onClick = show, onLongClick = edit).padding(end = if (compact) 0.dp else 12.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(title, style = titleStyle,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) stepMuted else stepInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun TimeGutter(hourHeight: Dp, gridTop: Dp, tickWidth: Dp, clockStyle: TextStyle,
    marker: ClosedRange<Dp>?) {
    val textHeight = with(LocalDensity.current) { clockStyle.lineHeight.toDp() }
    for (hour in 0..24) {
        val top = gridTop + hourHeight * hour
        HorizontalDivider(thickness = Dp.Hairline, color = grid, modifier = Modifier.fillMaxWidth().offset(y = top))
        // 红色标签靠近整点时暂时让出重叠的灰字，灰色小时线仍跨过整张网格。
        val labelY = top + 3.dp
        if (marker != null && labelY < marker.endInclusive && labelY + textHeight > marker.start) continue
        Box(Modifier.offset(y = labelY).width(tickWidth), contentAlignment = Alignment.Center) {
            Text(clockLabel((hour % 24) * 60), color = calendarGray, style = clockStyle, maxLines = 1,
                modifier = Modifier.semantics { if (hour == 24) contentDescription = "次日 00:00" })
        }
    }
}

// 时间轴使用比清单小两号的文字，卡片最小高度也按这套文字计算。
private val timelineTitle: TextStyle
    @Composable get() = stepItemTitle.let { it.copy(fontSize = (it.fontSize.value - 2).sp, lineHeight = (it.lineHeight.value - 2).sp) }
private val timelineMetadata: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.let { it.copy(fontSize = (it.fontSize.value - 2).sp,
        lineHeight = (it.lineHeight.value - 2).sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventCard(item: CalendarEntry, modifier: Modifier, show: (CalendarEntry) -> Unit, edit: (CalendarEntry) -> Unit) {
    // 浅底、色条和标题共用同一类别色；短事项优先留给标题和时间。
    val palette = calendarPalette(item.kind)
    val time = eventTime(item)
    val endpoints = remember(time) { time.split('–', limit = 2) }
    BoxWithConstraints(modifier.padding(horizontal = 2.dp).clip(stepEventShape)
        .semantics { contentDescription = "${item.title}，$time" }
        .background(palette.fill.copy(alpha = .86f)).combinedClickable(onClick = { show(item) }, onLongClick = { edit(item) })) {
        val titleStyle = timelineTitle
        val metadata = timelineMetadata
        val measurer = rememberTextMeasurer()
        val (timeLines, roomForPlace) = with(LocalDensity.current) {
            val titleHeight = titleStyle.lineHeight.toDp()
            val metadataHeight = metadata.lineHeight.toDp()
            val textWidth = maxWidth - 26.dp
            fun fits(value: String) = measurer.measure(value, style = metadata, maxLines = 1).size.width.toDp() <= textWidth
            // 先尝试一行，再尝试两个完整时刻。实在放不下时，由日期列表和详情提供完整时间。
            val lines = when {
                maxHeight >= titleHeight + metadataHeight + 12.dp && fits(time) -> listOf(time)
                maxHeight >= titleHeight + metadataHeight * 2 + 12.dp && endpoints.all(::fits) -> endpoints
                else -> emptyList()
            }
            lines to (lines.isNotEmpty() && maxHeight >= titleHeight + metadataHeight * (lines.size + 1) + 12.dp)
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(6.dp)) {
            CalendarStripe(palette.stripe)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(item.title, color = palette.ink, fontWeight = FontWeight.Bold, style = titleStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                timeLines.forEach { line ->
                    Text(line, color = palette.ink.copy(alpha = .8f), style = metadata, maxLines = 1, softWrap = false)
                }
                if (roomForPlace && item.place.isNotBlank()) Text(item.place, color = palette.ink, style = metadata,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun eventTime(item: CalendarEntry): String = if (item.start != null && item.end != null) {
    "${clockLabel(Math.floorMod(item.start, 1440L).toInt())}–${clockLabel(Math.floorMod(item.end, 1440L).toInt())}"
} else "全天"

private fun clockMinute(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
internal fun clockLabel(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

@Composable
internal fun BottomBar(page: Int, select: (Int) -> Unit) {
    BoxWithConstraints {
        val labelRows = if (maxWidth < 360.dp && LocalDensity.current.fontScale >= 1.5f) 2 else 1
        NavigationBar(containerColor = stepCell) {
            listOf("近日", "日程", "智能体", "目标").forEachIndexed { index, title ->
                NavigationBarItem(selected = page == index + 1, onClick = { select(index + 1) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFDCE9DF), selectedIconColor = stepAccent),
                    icon = { StepIcon(tabGlyphs[index]) }, label = {
                        // 选中态靠图标底色区分，所有标签保持同一黑色粗体。
                        Text(title, color = Color.Black, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, minLines = labelRows, maxLines = 2)
                    })
            }
        }
    }
}

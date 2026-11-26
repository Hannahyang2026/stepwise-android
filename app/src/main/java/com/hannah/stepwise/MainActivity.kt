package com.hannah.stepwise

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val green = stepAccent
private val paper = stepPaper
private val ink = stepInk
private val dateFormat = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)
internal fun label(day: Long): String {
    val date = LocalDate.ofEpochDay(day)
    return if (date.year == LocalDate.now().year) date.format(dateFormat) else "${date.year}年${date.format(dateFormat)}"
}
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

// 把通知入口转成一个状态值。通知从后台打开时，也能切回「今日」。
class MainActivity : ComponentActivity() {
    private var openToday by mutableIntStateOf(0)
    private var openAssistant by mutableIntStateOf(0)
    private var assistantId by mutableLongStateOf(0L)

    private fun route(intent: Intent) {
        if (intent.getBooleanExtra("today", false)) openToday++
        val requested = intent.getLongExtra("assistantId", 0)
        if (requested > 0) { assistantId = requested; openAssistant++ }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
        // 页面使用固定浅色主题，因此状态栏图标保持深色。
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT))
        setContent {
            // maxWidth 随分屏和旋转变化。扣除两侧页边距后，用实际内容宽度决定字号档位。
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val type = if (maxWidth - stepInset * 2 < 360.dp) stepCompactTypography else stepTypography
                MaterialTheme(colorScheme = lightColorScheme(primary = green, background = paper, surface = paper,
                    onSurface = ink, primaryContainer = Color(0xFFD9EDDE), onPrimaryContainer = green,
                    error = stepRed, errorContainer = Color(0xFFFFE7E7), onErrorContainer = stepInk),
                    shapes = stepShapes, typography = type) {
                    Stepwise(openToday, openAssistant, assistantId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }
}

// 主界面在本地保存成功后重新读取数据，同一套数据用于今日、日程和目标。
@Composable
private fun Stepwise(openToday: Int, openAssistant: Int, assistantId: Long) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val scope = rememberCoroutineScope()
    val messages = remember { SnackbarHostState() }
    val work = remember { Workspace(context) }
    val pages = rememberPagerState(initialPage = 1) { 5 }
    val calendar by remember { work.dao.calendar() }.collectAsState(initial = emptyList())
    val agents by remember { work.dao.agents() }.collectAsState(initial = emptyList())
    var selectedDay by rememberSaveable { mutableLongStateOf(today()) }
    var selectedAgent by rememberSaveable { mutableLongStateOf(0L) }
    var settingsRoute by rememberSaveable { mutableStateOf("") }
    var settingsReturn by rememberSaveable { mutableIntStateOf(1) }
    val notice by Reminders.notice.collectAsState()
    // 批量管理在独立窗口核对。关闭后，现有日历订阅会自动显示删除结果。
    var manageCalendar by rememberSaveable { mutableStateOf(false) }
    var calendarEdit by rememberSaveable { mutableStateOf<CalendarEntry?>(null) }
    // 保存后让发起操作的页面定位到这条日程；定位一次即清除请求。
    var revealOnTimeline by remember { mutableStateOf<CalendarEntry?>(null) }
    var revealOnAgenda by remember { mutableStateOf<CalendarEntry?>(null) }
    var lessonEdit by remember { mutableStateOf<Row?>(null) }
    var removeEntry by remember { mutableStateOf<CalendarEntry?>(null) }
    var removeLesson by remember { mutableStateOf<Row?>(null) }
    val changes = remember { Mutex() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var calendarDetail by remember { mutableStateOf<CalendarEntry?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // 离开聊天或表单时收起键盘，让下一页从正常高度开始布局。
    fun hideInput() {
        focus.clearFocus()
        keyboard?.hide()
    }
    // 直接定位下一帧，能取消手指滑动或惯性动画。点击近日把选中日期也归到今天。
    fun navigate(page: Int) {
        hideInput()
        if (page == 1) selectedDay = today()
        if (page != 0) {
            settingsRoute = ""
            settingsReturn = page
        }
        pages.requestScrollToPage(page)
    }
    LaunchedEffect(pages.settledPage) {
        // 手势离开设置也要清理子路由，并记下新页面供下次返回。
        val settled = pages.settledPage
        if (settled != 0) {
            settingsReturn = settled
            settingsRoute = ""
        }
        if (settled != 3) hideInput()
    }
    fun openSettings(route: String) {
        settingsReturn = pages.settledPage.coerceAtLeast(1)
        settingsRoute = route
        navigate(0)
    }
    BackHandler(enabled = pages.settledPage != 1) {
        if (pages.settledPage != 0) navigate(1)
        else if (settingsRoute == "skills" || settingsRoute == "mcp") settingsRoute = "tools"
        else if (settingsRoute.isNotEmpty()) settingsRoute = ""
        else navigate(settingsReturn)
    }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var edit by remember { mutableStateOf<Goal?>(null) }
    var detail by remember { mutableStateOf<Row?>(null) }
    var newGoal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun submit(change: suspend () -> Unit = {}) {
        scope.launch {
            try {
                changes.withLock {
                    busy = true
                    change()
                    val previous = snapshot?.day
                    val latest = store.snapshot(today())
                    if (previous != null && latest.day != previous && selectedDay == previous) selectedDay = latest.day
                    snapshot = latest
                }
                Reminders.check(context)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                scope.launch { messages.showSnackbar("本地数据读取或保存失败，请重试") }
            } finally { busy = changes.isLocked }
        }
    }

    fun editRow(row: Row) {
        if (row.lesson == null) edit = row.goal else lessonEdit = row
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Reminders.schedule(context)
        submit()
    }
    LaunchedEffect(Unit) {
        // 恢复上次中断的清理，再恢复还存在的助手任务。空列表是正常状态。
        work.resumeAssistantCleanup()
        AssistantJobs(context).restoreAll()
        submit()
    }
    // 前台检查按手机分钟触发，后台由闹钟广播负责。
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last: java.time.LocalDateTime? = null
            while (true) {
                val minute = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                if (minute != last) { last = minute; submit() }
                delay(2_000)
            }
        }
    }
    LaunchedEffect(calendar) { Reminders.check(context) }
    LaunchedEffect(openToday) {
        if (openToday > 0) { detail = null; edit = null; navigate(1) }
    }
    LaunchedEffect(openAssistant) {
        if (openAssistant > 0) { selectedAgent = assistantId; navigate(3) }
    }

    // 停靠键盘弹出时先让出聊天底栏。告诉子页面 Scaffold 已占用的内边距，
    // 这样聊天页的 imePadding 只补输入法实际盖住的剩余高度。
    val density = LocalDensity.current
    val dockedKeyboard = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
    val hideChatBar = pages.settledPage == 3 && dockedKeyboard
    Scaffold(containerColor = paper, snackbarHost = { SnackbarHost(messages) }, bottomBar = {
        if (!hideChatBar) BottomBar(pages.settledPage, ::navigate)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            notice?.let { reminder ->
                StepGroup(Modifier.padding(horizontal = stepInset, vertical = 4.dp)) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StepIcon(StepGlyph.Bell, color = stepRed)
                        Text("每日提醒 · 今天还有 ${reminder.count} 项未完成", Modifier.weight(1f), color = stepRed)
                        TextButton(onClick = { Reminders.dismiss(context, reminder.day) }) { Text("关闭提醒") }
                    }
                }
            }
            HorizontalPager(state = pages, beyondViewportPageCount = 4, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                Column(Modifier.fillMaxSize().padding(horizontal = stepInset)) {
                    if (page != 0 && page != 3) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            // 智能体页在自己的顶栏放置设置与助手选择；其他页在这里共用设置按钮。
                            StepSettingsButton { openSettings("") }
                            Spacer(Modifier.weight(1f))
                            // 两个圆形操作共用尺寸。新增菜单在点选类型时才创建表单。
                            if (page == 2) AgendaActions({ manageCalendar = true }) { timed ->
                                val date = today()
                                calendarEdit = if (timed) CalendarWire.newEvent(date) else CalendarEntry(title = "", day = date)
                            }
                        }
                    }
                    val data = snapshot
                    when {
                        page == 0 -> SettingsPage(work, agents, settingsRoute, { navigate(settingsReturn) },
                            { settingsRoute = it }, { selectedAgent = it })
                        page == 3 -> {
                            val currentAgent = agents.firstOrNull { it.id == selectedAgent } ?: agents.firstOrNull()
                            key(currentAgent?.id) {
                                AssistantChat(work, agents, selectedAgent, { selectedAgent = it }, selectedDay,
                                    showSettings = { openSettings("") })
                            }
                        }
                        data == null -> if (busy) CircularProgressIndicator() else TextButton(onClick = { submit() }) { Text("重试") }
                        page == 1 -> TodayTimeline(data, calendar, selectedDay, { selectedDay = it }, { detail = it },
                            { row -> submit { store.toggle(row, today()) } },
                            { item -> submit { work.dao.mark(item.id, !item.done) } }, { calendarDetail = it },
                            { calendarEdit = CalendarEntry(title = "", day = data.day) },
                            { calendarEdit = CalendarWire.newEvent(selectedDay) }, { calendarEdit = it }, ::editRow,
                            revealOnTimeline, { revealOnTimeline = null })
                        page == 2 -> Agenda(data, calendar, { detail = it }, { row -> submit { store.toggle(row, today()) } },
                            { calendarDetail = it }, { item -> submit { work.dao.mark(item.id, !item.done) } },
                            { calendarEdit = it }, ::editRow, revealOnAgenda, { revealOnAgenda = null }, { edit = it })
                        else -> GoalCards(data, { newGoal = true }, { edit = it })
                    }
                }
            }
        }
    }

    if (manageCalendar) CalendarManagement(work, calendar, close = { manageCalendar = false }, deleted = { count ->
        manageCalendar = false
        scope.launch { messages.showSnackbar("已删除 ${count} 项日程") }
    })
    calendarEdit?.let { item ->
        key(item.id) {
            CalendarItemEditor(item, close = { calendarEdit = null },
                remove = if (item.id == 0L) null else ({ calendarEdit = null; removeEntry = item })) { updated ->
                submit {
                    // 同一个表单的第二次快速点按进入队列时，第一次已经保存并关闭了它。
                    if (calendarEdit !== item) return@submit
                    if (updated.id == 0L) work.dao.addEntries(listOf(updated)) else work.dao.update(updated)
                    if (updated.start != null) {
                        if (pages.settledPage == 1) {
                            selectedDay = updated.day
                            revealOnTimeline = updated
                        } else if (pages.settledPage == 2) revealOnAgenda = updated
                    }
                    calendarEdit = null
                }
            }
        }
    }
    lessonEdit?.let { row ->
        row.lesson?.let { lesson ->
            DraftEditor(Draft(lesson.title, lesson.action, lesson.minutes, lesson.source),
                close = { lessonEdit = null }, onSave = { value ->
                    submit { store.editLesson(lesson, value); lessonEdit = null }
                }, label = "保存", remove = { lessonEdit = null; removeLesson = row })
        }
    }
    removeEntry?.let { item ->
        ConfirmRemoval(item.title, { removeEntry = null }) { submit { work.dao.delete(item); removeEntry = null } }
    }
    removeLesson?.let { row ->
        row.lesson?.let { lesson ->
            ConfirmRemoval(lesson.title, { removeLesson = null }) {
                submit { store.deleteLesson(row.goal, lesson, today()); removeLesson = null }
            }
        }
    }

    if (newGoal) FormDialog("新建", { newGoal = false }, actions = {}) {
        // 三类目标从同一入口创建，倒计时只填写名称和日期。
        fun startGoal(habit: Boolean = false, countdown: Boolean = false) {
            edit = Goal(title = "", habit = habit, countdown = countdown, start = today())
            newGoal = false
        }
        StepGroup {
            Column {
                SettingLine(StepGlyph.Clock, "倒计时") { startGoal(countdown = true) }
                HorizontalDivider(color = stepLine)
                SettingLine(StepGlyph.Near, "习惯") { startGoal(habit = true) }
                HorizontalDivider(color = stepLine)
                SettingLine(StepGlyph.Agenda, "自学计划") { startGoal() }
            }
        }
    }
    edit?.let { goal ->
        // 共用保存与删除流程，在成功写入后再关闭表单。快速点两次也只处理当前打开的目标。
        val save: (Goal, List<Draft>) -> Unit = { changed, drafts ->
            submit {
                if (edit === goal) {
                    store.save(changed, drafts, today())
                    edit = null
                }
            }
        }
        val delete: () -> Unit = { submit { store.delete(goal); edit = null } }
        if (goal.countdown) CountdownEditor(goal, close = { edit = null }, save = { save(it, emptyList()) }, delete = delete)
        else GoalEditor(goal, close = { edit = null }, save = save, delete = delete,
            restoreHabit = { day, saved -> submit { store.restoreHabit(goal.id, day, today()); saved() } })
    }
    detail?.let { row ->
        FormDialog(row.lesson?.title ?: row.goal.title, { detail = null }, actions = {}) {
            Text(label(row.day), fontWeight = FontWeight.Bold)
            row.lesson?.let { step ->
                if (step.action.isNotBlank()) Text(step.action)
                step.minutes?.let { Text("预计 $it 分钟", color = green) }
                if (step.source.isNotBlank()) Text("课程原文\n${step.source}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    calendarDetail?.let { item ->
        FormDialog(item.title, { calendarDetail = null }, actions = {
            // 查看详情时即可进入编辑；列表的长按入口也复用同一份记录。
            Button(shape = stepControlShape, onClick = { calendarDetail = null; calendarEdit = item }) { Text("编辑") }
        }) {
            if (item.start != null && item.end != null) {
                Text(CalendarWire.time(item.start).toString().replace('T', ' ') + " – " +
                    CalendarWire.time(item.end).toString().replace('T', ' '), fontWeight = FontWeight.Bold)
            } else Text(label(item.day) + " · 全天", fontWeight = FontWeight.Bold)
            if (item.place.isNotBlank()) Text(item.place, style = MaterialTheme.typography.bodyMedium)
            if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall, color = stepMuted)
        }
    }
}

// 日程页的两个顶栏操作都保留 48 dp 的点按区。
@Composable
private fun AgendaActions(manage: () -> Unit, create: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        StepRoundAction(StepGlyph.Wrench, "批量管理",
            ink = stepMuted, outline = stepMuted, onClick = manage)
        Box {
            StepAddButton("新增事项") { menu = true }
            // 弹层右沿跟加号的可见圆面对齐。两个等宽胶囊都从中间点按。
            StepAnchoredPanel(menu, onDismiss = { menu = false }, alignEnd = true, modifier = Modifier.width(144.dp)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    StepMenuPill("新增待办", stretch = true, horizontalPadding = 8.dp) { menu = false; create(false) }
                    StepMenuPill("新增日程", stretch = true, horizontalPadding = 8.dp) { menu = false; create(true) }
                }
            }
        }
    }
}

// 每天共用一张卡片。待办在最上方，之后是倒计时和按开始时间排列的日程。
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Agenda(data: Snapshot, entries: List<CalendarEntry>, details: (Row) -> Unit, toggle: (Row) -> Unit,
    show: (CalendarEntry) -> Unit, mark: (CalendarEntry) -> Unit, editEntry: (CalendarEntry) -> Unit,
    editRow: (Row) -> Unit, reveal: CalendarEntry?, revealed: () -> Unit, editCountdown: (Goal) -> Unit) {
    val dates = remember(data, entries) {
        (data.agenda() + entries.flatMap { item ->
            item.day..(item.end?.let { Math.floorDiv(it - 1, 1440L) } ?: item.day)
        }).distinct().sorted()
    }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val visible = if (showHistory) dates.filter { it < data.day }.reversed() else dates.filter { it >= data.day }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 保存过去的事项后切入历史，等日期组出现再滚动到对应位置。
    LaunchedEffect(reveal, visible) {
        val day = reveal?.day ?: return@LaunchedEffect
        showHistory = day < data.day
        val index = visible.indexOf(day)
        if (index >= 0 && showHistory == (day < data.day)) {
            listState.scrollToItem(index)
            revealed()
        }
    }
    // 缩短可见胶囊，用独立的外层维持 48 dp 点按区。两项等宽，边缘仍对齐日期卡片。
    val style = MaterialTheme.typography.bodyMedium
    val filterHeight = with(LocalDensity.current) { maxOf(34.dp, style.lineHeight.toDp() + 8.dp) }
    val filterTouchHeight = maxOf(stepIconTouchSize, filterHeight)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "接下来", true to "历史记录").forEach { (history, title) ->
            val active = history == showHistory
            Box(Modifier.weight(1f).height(filterTouchHeight)
                .selectable(active, role = Role.Tab, onClick = { showHistory = history }), contentAlignment = Alignment.Center) {
                Surface(Modifier.fillMaxWidth().height(filterHeight), shape = CircleShape,
                    color = if (active) stepAccent.copy(alpha = .12f) else stepPaper,
                    border = BorderStroke(1.dp, if (active) stepAccent.copy(alpha = .25f) else stepLine)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(title, style = style, color = if (active) stepAccent else stepInk)
                    }
                }
            }
        }
    }
    if (visible.isEmpty()) Text(if (showHistory) "还没有历史记录" else "没有安排", Modifier.padding(vertical = 16.dp))
    LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(visible, key = { it }) { date ->
            val tasks = data.rows(date)
            val todos = entries.filter { it.start == null && it.day == date }
            val countdowns = data.countdownsOn(date)
            // 复用时间轴的切分规则，午夜结束的事项只计入结束之前的日期。
            val timed = placeEvents(entries, date)
            val isToday = date == data.day
            val lunar = remember(date) { lunarName(LocalDate.ofEpochDay(date), includeMonth = true) }
            val heading = label(date)
            val dateStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            val markerInset = (agendaMarkerWidth - stepCheckedDiameter) / 2
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = markerInset, end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 公历和星期可以分到两行。今天以朱红粗体标出，其他日期保持黑色常规字。
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val color = if (isToday) stepRed else Color.Black
                    Text(heading.substringBeforeLast(' '), style = dateStyle, color = color)
                    Text(heading.substringAfterLast(' '), style = dateStyle, color = color)
                }
                Text(lunar, style = MaterialTheme.typography.bodySmall, color = stepMuted, softWrap = false)
            }
            StepGroup {
                Column {
                    // 从第二条起加分隔线，圆角卡片首尾保留干净的边界。
                    var firstRow = true
                    tasks.forEach { row ->
                        if (!firstRow) AgendaDivider()
                        firstRow = false
                        TodoLine(row.lesson?.title ?: row.goal.title, row.done, date == data.day || row.done || row.goal.habit && date < data.day,
                            { toggle(row) }, { details(row) }, { editRow(row) }, compact = false)
                    }
                    todos.forEach { item ->
                        if (!firstRow) AgendaDivider()
                        firstRow = false
                        TodoLine(item.title, item.done, true, { mark(item) }, { show(item) }, { editEntry(item) }, compact = false)
                    }
                    countdowns.forEach { goal ->
                        if (!firstRow) AgendaDivider()
                        firstRow = false
                        // 截止事项采用与待办相同的紧凑下限，正文增高时仍由行自动扩展。
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .combinedClickable(onClick = { editCountdown(goal) }).padding(end = 12.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                            Box(Modifier.width(agendaMarkerWidth), contentAlignment = Alignment.Center) {
                                StepIcon(StepGlyph.Clock, color = stepAccent)
                            }
                            Text(goal.title, Modifier.weight(1f), style = stepItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("截止", style = MaterialTheme.typography.bodySmall, color = stepRed.copy(alpha = .8f))
                        }
                    }
                    timed.forEach { block ->
                        if (!firstRow) AgendaDivider()
                        firstRow = false
                        AgendaEventRow(block, show, editEntry)
                    }
                    if (firstRow) Text("没有安排", Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AgendaDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = androidx.compose.ui.unit.Dp.Hairline, color = stepLine)
}

// 类别色圆点与勾选位同列同心。时段作为标题下面的备注，与地点分别占行。
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaEventRow(block: Placement, show: (CalendarEntry) -> Unit, edit: (CalendarEntry) -> Unit) {
    val entry = block.item
    val palette = calendarPalette(entry.kind)
    // 收紧事项的上下留白，标题、时段与地点仍按自身文字高度排版。
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .combinedClickable(onClick = { show(entry) }, onLongClick = { edit(entry) })
        .padding(end = 12.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(agendaMarkerWidth), contentAlignment = Alignment.Center) {
            Box(Modifier.size(stepCheckedDiameter).background(palette.stripe, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = stepItemTitle, color = stepInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val timeInk = stepInk.copy(alpha = .70f)
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StepIcon(StepGlyph.Alarm, color = timeInk, diameter = 14.dp)
                Text("${clockLabel(block.from)} – ${clockLabel(block.to)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = timeInk, softWrap = false)
            }
            if (entry.place.isNotBlank()) Text(entry.place, style = MaterialTheme.typography.bodySmall,
                color = stepMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConfirmRemoval(title: String, close: () -> Unit, remove: () -> Unit) {
    FormDialog("删除事项", close, actions = { Button(shape = stepControlShape, onClick = remove) { Text("确认删除") } }) {
        Text("删除「$title」？")
    }
}

// 编辑目标和待保存的课程草稿。AI 和手动添加共用预览列表，保存时一次写入。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalEditor(goal: Goal, close: () -> Unit, save: (Goal, List<Draft>) -> Unit,
    delete: () -> Unit, restoreHabit: (Long, () -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(goal.title) }
    var start by rememberSaveable { mutableLongStateOf(goal.start) }
    var deadline by rememberSaveable { mutableStateOf(goal.deadline) }
    var mask by rememberSaveable { mutableIntStateOf(goal.weekdays) }
    var daily by rememberSaveable { mutableStateOf(goal.daily.toString()) }
    var source by rememberSaveable { mutableStateOf("") }
    var requirements by rememberSaveable { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateListOf<Draft>() }
    val cadence = daily.toIntOrNull()?.takeIf { it in 1..30 }

    androidx.compose.ui.window.Dialog(onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = paper, shape = stepDialogShape, modifier = Modifier
            .padding(12.dp).fillMaxWidth().heightIn(max = 780.dp).imePadding()) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle(if (goal.habit) (if (goal.id == 0L) "新建习惯" else "编辑习惯")
                    else (if (goal.id == 0L) "新建自学计划" else "编辑自学计划"), close)
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Field(name, { name = it }, "目标名称")
                        TextButton(onClick = {
                            val date = LocalDate.ofEpochDay(start)
                            DatePickerDialog(context, { _, year, month, day ->
                                start = LocalDate.of(year, month + 1, day).toEpochDay()
                            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                        }) { Text("开始日期 · ${label(start)}", fontWeight = FontWeight.Bold) }
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val date = LocalDate.ofEpochDay(deadline ?: today())
                                DatePickerDialog(context, { _, year, month, day ->
                                    deadline = LocalDate.of(year, month + 1, day).toEpochDay()
                                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                            }) { Text(deadline?.let { "截止日期 · ${label(it)}" } ?: "设置截止日期",
                                fontWeight = if (deadline != null) FontWeight.Bold else FontWeight.Medium) }
                            if (deadline != null) TextButton(onClick = { deadline = null }) { Text("清除截止日") }
                        }
                        Text("每周安排")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            weekdays.forEachIndexed { index, day ->
                                val bit = 1 shl index
                                FilterChip(shape = stepControlShape, selected = mask and bit != 0, onClick = { mask = mask xor bit }, label = { Text(day) })
                            }
                        }
                        // 旧版已删掉的记录只能由用户指定原日期；日期选择器只开放过去。
                        if (goal.habit && goal.id != 0L && goal.start < today()) {
                            TextButton(onClick = {
                                val lastDay = LocalDate.now().minusDays(1)
                                DatePickerDialog(context, { _, year, month, day ->
                                    restoreHabit(LocalDate.of(year, month + 1, day).toEpochDay()) {
                                        message = "已恢复所选日期的打卡"
                                    }
                                }, lastDay.year, lastDay.monthValue - 1, lastDay.dayOfMonth).apply {
                                    val zone = java.time.ZoneId.systemDefault()
                                    datePicker.minDate = LocalDate.ofEpochDay(goal.start).atStartOfDay(zone).toInstant().toEpochMilli()
                                    datePicker.maxDate = lastDay.atStartOfDay(zone).toInstant().toEpochMilli()
                                }.show()
                            }) { Text("恢复历史打卡") }
                            if (message.isNotBlank()) Text(message, color = green)
                        }
                        if (!goal.habit) Field(daily, { daily = it }, "每天几课（1–30）", numeric = true)
                    }
                    if (!goal.habit) {
                        item {
                            Text(if (goal.id == 0L) "课程任务" else "追加课程任务", style = MaterialTheme.typography.titleMedium)
                            Field(source, { source = it }, "粘贴课程目录或介绍", lines = 3)
                            Field(requirements, { requirements = it }, "学习基础与每次可用时间（选填）", lines = 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(shape = stepControlShape, enabled = !generating && source.isNotBlank(), onClick = {
                                    generating = true
                                    message = ""
                                    scope.launch {
                                        try {
                                            val generated = Ai.generate(Vault(context).read(), source, requirements)
                                            drafts.addAll(generated)
                                            message = "已加入 ${generated.size} 条草稿，可以修改后保存。"
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: java.io.IOException) {
                                            message = "连接 API 失败。请检查网络与地址，或手动添加。"
                                        } catch (error: Exception) {
                                            message = error.message ?: "生成失败，请重试或手动添加。"
                                        } finally {
                                            generating = false
                                        }
                                    }
                                }) { Text(if (generating) "生成中…" else "AI 生成计划") }
                                OutlinedButton(shape = stepControlShape, onClick = { selected = -1 }) { Text("手动添加") }
                            }
                            if (message.isNotBlank()) Text(message, color = green)
                        }
                        items(drafts.size) { index ->
                            val draft = drafts[index]
                            Surface(color = stepCell, shape = stepShape) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text("${index + 1}. ${draft.title}", style = MaterialTheme.typography.titleMedium)
                                    if (draft.action.isNotBlank()) Text(draft.action)
                                    draft.minutes?.let { Text("预计 $it 分钟", color = green) }
                                    if (draft.source.isNotBlank()) Text("原文 · ${draft.source}", style = MaterialTheme.typography.bodySmall)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { selected = index }) { Text("编辑") }
                                        TextButton(enabled = index > 0, onClick = {
                                            drafts.add(index - 1, drafts.removeAt(index))
                                        }) { Text("上移") }
                                        TextButton(enabled = index < drafts.lastIndex, onClick = {
                                            drafts.add(index + 1, drafts.removeAt(index))
                                        }) { Text("下移") }
                                        TextButton(onClick = { drafts.removeAt(index) }) { Text("移除") }
                                    }
                                }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(shape = stepControlShape, enabled = !generating && name.isNotBlank() && mask != 0 &&
                        (goal.habit || cadence != null && (goal.id != 0L || drafts.isNotEmpty())), onClick = {
                            save(goal.copy(title = name.trim(), start = start, weekdays = mask, daily = cadence ?: 1, deadline = deadline), drafts.toList())
                        }) { Text("保存") }
                    TextButton(onClick = close) { Text("取消") }
                    if (goal.id != 0L) TextButton(onClick = { confirmDelete = true }) { Text("删除目标", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    selected?.let { index ->
        DraftEditor(drafts.getOrNull(index), close = { selected = null }, onSave = { item ->
            // 新增任务用独立标记。即使 AI 在编辑期间追加草稿，也不会覆盖生成结果。
            if (index < 0) drafts.add(item) else drafts[index] = item
            selected = null
        })
    }
    if (confirmDelete) AlertDialog(shape = stepDialogShape, onDismissRequest = { confirmDelete = false }, title = { Text("删除目标？") },
        text = { Text("该目标的课程与完成记录会一并删除。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}

// 编辑单条任务。用时和原文可留空，方便手动建立自己的计划。
@Composable
private fun DraftEditor(item: Draft?, close: () -> Unit, onSave: (Draft) -> Unit, label: String = "加入预览", remove: (() -> Unit)? = null) {
    var title by remember { mutableStateOf(item?.title.orEmpty()) }
    var action by remember { mutableStateOf(item?.action.orEmpty()) }
    var minutes by remember { mutableStateOf(item?.minutes?.toString().orEmpty()) }
    var source by remember { mutableStateOf(item?.source.orEmpty()) }
    val value = minutes.toIntOrNull()?.takeIf { it in 1..1440 }
    FormDialog(if (item == null) "添加任务" else "编辑任务", close, actions = {
        if (remove != null) TextButton(onClick = remove) { Text("删除") }
        Button(shape = stepControlShape, enabled = title.isNotBlank() && (minutes.isBlank() || value != null),
            onClick = { onSave(Draft(title.trim(), action.trim(), value, source.trim())) }) { Text(label) }
    }) {
        Field(title, { title = it }, "任务名称")
        Field(action, { action = it }, "具体要做什么", lines = 2)
        Field(minutes, { minutes = it }, "预计分钟数", numeric = true)
        Field(source, { source = it }, "课程原文", lines = 2)
    }
}

// API 地址、模型和密钥沿用旧版字段，覆盖安装后仍可直接使用。
@Composable
internal fun ApiScreen(close: () -> Unit) {
    val context = LocalContext.current
    val vault = remember { Vault(context) }
    val initial = remember { runCatching { vault.read() }.getOrDefault(ApiSettings()) }
    var base by remember { mutableStateOf(initial.base) }
    var model by remember { mutableStateOf(initial.model) }
    var token by remember { mutableStateOf(initial.key) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("API 配置", close)
        TextButton(onClick = {
            val preset = ApiSettings()
            if (base != preset.base) token = ""
            base = preset.base
            model = preset.model
        }) { Text("填入 DeepSeek 预设") }
        Field(base, { if (it != base) token = ""; base = it }, "HTTPS API 地址")
        Field(model, { model = it }, "模型名称")
        OutlinedTextField(token, { token = it }, shape = stepControlShape, label = { Text("API 密钥") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("发送或生成时，当前请求和所用记忆会发给所选服务商。", style = MaterialTheme.typography.bodySmall)
        Button(shape = stepControlShape, onClick = {
            try {
                Ai.endpoint(base)
                require(model.isNotBlank()) { "请填写模型名称" }
                vault.save(ApiSettings(base.trim(), model.trim(), token.trim()))
                close()
            } catch (failure: Exception) { error = assistantError(failure) }
        }) { Text("保存") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

// 表单字段统一宽度和键盘类型。数字字段弹出数字键盘，多行字段适合课程材料。
@Composable
internal fun Field(value: String, update: (String) -> Unit, name: String, lines: Int = 1, numeric: Boolean = false,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, showLabel: Boolean = true) {
    // 外部已有可见区名时只保留读屏名称，避免在输入框中重复标题。
    val input = Modifier.fillMaxWidth().then(if (showLabel) Modifier else Modifier.semantics { contentDescription = name })
    OutlinedTextField(value, update, shape = stepControlShape, modifier = input,
        label = if (showLabel) ({ Text(name) }) else null,
        textStyle = textStyle, singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else 6,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}

package com.hannah.stepwise

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.scrollBy
import androidx.core.content.edit
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal data class ToolPrompt(val tool: RemoteTool, val arguments: JSONObject, val answer: CompletableDeferred<Boolean>)

// 聊天、计划导入和记忆提取走同一助手。对话和草稿从 Room 读，按钮状态只保存在屏幕内。
@Composable
internal fun AssistantChat(work: Workspace, agents: List<AssistantProfile>, selected: Long,
    select: (Long) -> Unit, day: Long, showSettings: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { AssistantEngine(context.applicationContext) }
    val agent = agents.firstOrNull { it.id == selected } ?: agents.firstOrNull()
    // 空列表提供独立日程入口，内置规划不会出现在助手管理列表里。
    if (agent == null) {
        AssistantEmpty(work, engine, select, day, showSettings)
        return
    }
    val history by remember(agent.id) { work.dao.messages(agent.id) }.collectAsState(initial = emptyList())
    val batches by remember(agent.id) { work.dao.batches(agent.id) }.collectAsState(initial = emptyList())
    val runs by remember(agent.id) { work.dao.runs(agent.id) }.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 助手菜单与编辑表单共用当前助手；保存后由数据库订阅更新名称。
    var choosing by remember { mutableStateOf(false) }
    var editingProfile by rememberSaveable { mutableLongStateOf(0L) }
    var savingProfile by remember { mutableStateOf(false) }
    // 按助手把未发送内容写入本机私有设置，长计划不放入 Android 的窗口状态包。
    val drafts = remember { context.getSharedPreferences("assistant_input", android.content.Context.MODE_PRIVATE) }
    val inputKey = "input-${agent.id}"
    val modeKey = "plan-${agent.id}"
    var input by remember { mutableStateOf(drafts.getString(inputKey, "").orEmpty()) }
    var planMode by remember { mutableStateOf(drafts.getBoolean(modeKey, false)) }
    fun updateInput(value: String) {
        input = value
        drafts.edit { putString(inputKey, value) }
    }
    fun updateMode(value: Boolean) {
        planMode = value
        drafts.edit { putBoolean(modeKey, value) }
    }
    val unavailableServers = remember { mutableStateListOf<String>() }
    val noteUnavailable: suspend (String) -> Unit = { name ->
        withContext(Dispatchers.Main) { if (name !in unavailableServers) unavailableServers.add(name) }
    }
    var busy by remember { mutableStateOf(false) }
    var liveReply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var proposal by remember { mutableStateOf<List<String>?>(null) }
    var request by remember { mutableStateOf<ToolPrompt?>(null) }
    var preview by remember { mutableStateOf<AgendaBatch?>(null) }
    var inbox by remember { mutableStateOf(false) }

    // 模型在后台线程等待确认，Compose 状态只在主线程变更。
    val confirm: suspend (RemoteTool, JSONObject) -> Boolean = { tool, args ->
        withContext(Dispatchers.Main) {
            val decision = CompletableDeferred<Boolean>()
            request = ToolPrompt(tool, args, decision)
            try { decision.await() } finally { request = null }
        }
    }

    fun launchRequest(run: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        unavailableServers.clear()
        liveReply = ""
        scope.launch {
            try { run() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = assistantError(e) }
            finally { busy = false }
        }
    }

    fun extractMemory() = launchRequest {
        val messages = work.dao.recent(agent.id).asReversed().map { AssistantWire.message(it.role, it.text) }
        val raw = engine.run(agent, messages, """从本轮聊天提取长期有用的偏好。只输出 JSON {"memories":["一条可独立理解的记忆"]}。没有候选时返回空数组。""",
            useTools = false, withSkills = false)
        proposal = jsonStrings(JSONObject(raw).getJSONArray("memories").toString())
            .filter { it.isNotBlank() }.take(12)
    }

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.scrollToItem(history.lastIndex)
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        // 极矮窗口把存为记忆收入助手面板，为输入区和键盘保留空间。
        val shortViewport = maxHeight < 148.dp
        val menuHeight = minOf(360.dp, maxHeight).coerceAtLeast(stepIconTouchSize)
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(stepIconTouchSize),
                verticalAlignment = Alignment.CenterVertically) {
                // 三个顶栏入口共享圆心高度，助手胶囊的可见面与圆钮等高。
                StepSettingsButton(showSettings)
                Box(Modifier.weight(1f)) {
                    // 名称和小尖角共用胶囊，弹层以该胶囊左缘为定位基准。
                    Box {
                        StepMenuPill(agent.name, fixedHeight = true, enabled = !busy,
                            accessibilityLabel = "切换智能体，当前为${agent.name}", trailingGlyph = StepGlyph.ChevronDown,
                            onLongClickLabel = "配置智能体：${agent.name}",
                            onLongClick = { editingProfile = agent.id }, onClick = { choosing = true })
                        AssistantPicker(choosing, agents, agent.id, maxHeight = menuHeight,
                            close = { choosing = false },
                            choose = { id -> choosing = false; error = ""; select(id) },
                            edit = { id -> choosing = false; editingProfile = id },
                            quickMemory = if (shortViewport && !busy && history.isNotEmpty())
                                ({ choosing = false; extractMemory() }) else null)
                    }
                }
                StepRoundAction(StepGlyph.Draft, "草稿与运行记录", outline = stepMuted, ink = stepMuted,
                    onClick = { inbox = true })
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (history.isEmpty()) item { Text("输入你的目标或先粘贴课程计划。", style = MaterialTheme.typography.bodyMedium) }
                items(history, key = { it.id }) { message ->
                    val mine = message.role == "user"
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else stepCell,
                            modifier = Modifier.fillMaxWidth(.88f)) {
                            if (mine) Text(message.text, Modifier.padding(13.dp))
                            else ChatMarkdown(message.text, Modifier.padding(13.dp))
                        }
                    }
                }
                // 先显示服务器传来的片段。完整回复写进历史后，移除临时气泡。
                if (liveReply.isNotBlank()) item(key = "live-reply") {
                    Surface(shape = MaterialTheme.shapes.medium, color = stepCell,
                        modifier = Modifier.fillMaxWidth(.88f)) {
                        ChatMarkdown(liveReply, Modifier.padding(13.dp))
                    }
                }
                if (unavailableServers.isNotEmpty()) item {
                    Text("MCP 服务暂不可用：${unavailableServers.joinToString("、")}。本次已跳过。", color = stepMuted)
                }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            LaunchedEffect(error, unavailableServers.size, liveReply) {
                if ((liveReply.isNotBlank() || error.isNotBlank() || unavailableServers.isNotEmpty()) &&
                    !listState.isScrollInProgress) {
                    withFrameNanos { }
                    val index = listState.layoutInfo.totalItemsCount - 1
                    if (index >= 0) {
                        listState.scrollToItem(index)
                        listState.scrollBy(100_000f)
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            // 图标和灰字归入同一个胶囊，保留原来的候选记忆确认流程。
            if (!shortViewport) MemoryPill(Modifier.padding(start = stepRoundInset),
                enabled = !busy && history.isNotEmpty(), onClick = ::extractMemory)
            AssistantComposer(input, ::updateInput, planMode, { updateMode(!planMode); error = "" }, busy, send = {
                    val message = input.trim()
                    launchRequest {
                        if (planMode) {
                            val batch = engine.makeAgenda(agent, message, day, confirm, noteUnavailable)
                            work.saveDraft(batch)
                            preview = batch
                        } else {
                            val past = work.dao.recent(agent.id).asReversed()
                            val reply = engine.run(agent, past.map { AssistantWire.message(it.role, it.text) } +
                                AssistantWire.message("user", message), confirm = confirm, unavailable = noteUnavailable,
                                liveText = { part -> withContext(Dispatchers.Main) { liveReply = part } })
                            work.saveExchange(agent.id, message, reply)
                            liveReply = ""
                        }
                        updateInput("")
                    }
            })
        }
    }

    // 长按直接查看对应助手，无须先切换会话。保存等待期间避免重复写入。
    val profileToEdit = agents.firstOrNull { it.id == editingProfile }
    if (profileToEdit != null) ProfileDialog(profileToEdit, close = {
        if (!savingProfile) editingProfile = 0L
    }) { profile ->
        if (!savingProfile) {
            savingProfile = true
            scope.launch {
                try {
                    work.dao.update(profile)
                    editingProfile = 0L
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    error = assistantError(e)
                    editingProfile = 0L
                } finally {
                    savingProfile = false
                }
            }
        }
    }

    request?.let { call ->
        AlertDialog(shape = stepDialogShape, onDismissRequest = { call.answer.complete(false) }, title = { Text("确认调用工具") },
            text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                Text(call.tool.label, fontWeight = FontWeight.Bold)
                Text(call.arguments.toString(2))
            } }, confirmButton = { Button(shape = stepControlShape, onClick = { call.answer.complete(true) }) { Text("允许本次调用") } },
            dismissButton = { TextButton(onClick = { call.answer.complete(false) }) { Text("拒绝") } })
    }

    proposal?.let { candidates ->
        key(candidates) {
            val notes = remember { mutableStateListOf<String>().apply { addAll(candidates) } }
            val selectedNotes = remember { mutableStateListOf<Boolean>().apply { repeat(candidates.size) { add(true) } } }
            FormDialog("确认要保存的记忆", close = { proposal = null }, actions = {
                Button(shape = stepControlShape, enabled = notes.indices.any { selectedNotes[it] && notes[it].isNotBlank() }, onClick = {
                    val chosen = notes.indices.filter { selectedNotes[it] && notes[it].isNotBlank() }.map { notes[it].trim() }
                    scope.launch { work.saveMemories(agent.id, chosen); proposal = null }
                }) { Text("保存所选记忆") }
            }) {
                if (notes.isEmpty()) Text("这段聊天没有需要长期保存的记忆。")
                notes.indices.forEach { index ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        StepCheckbox(selectedNotes[index], { selectedNotes[index] = it })
                        Field(notes[index], { notes[index] = it }, "记忆 ${index + 1}", lines = 2)
                    }
                }
            }
        }
    }

    if (inbox) AssistantInbox(batches, runs, close = { inbox = false }) { batch ->
        inbox = false
        preview = batch
    }

    preview?.let { batch ->
        key(batch.id) {
            CalendarPreview(batch, work, close = { preview = null })
        }
    }
}

// 空状态和常规聊天统一从收件箱找回待确认草稿。
@Composable
private fun AssistantInbox(batches: List<AgendaBatch>, runs: List<JobRun>, close: () -> Unit, open: (AgendaBatch) -> Unit) {
    FormDialog("日程草稿与运行记录", close, actions = {}) {
        if (batches.isEmpty() && runs.isEmpty()) Text("还没有待确认的草稿或运行记录。")
        batches.forEach { batch ->
            OutlinedButton(shape = stepControlShape, onClick = { open(batch) }) {
                Text("预览日程草稿 · ${CalendarWire.parse(batch.items, allowEmpty = true).size} 项")
            }
        }
        runs.forEach { run ->
            HorizontalDivider()
            Text("${run.name} · ${run.status}", fontWeight = FontWeight.Bold)
            Text(run.slot.replace('T', ' '))
            if (run.result.isNotBlank()) Text(run.result)
            if (run.tools.isNotBlank()) Text(run.tools, style = MaterialTheme.typography.bodySmall)
        }
    }

}

// 空状态的两种规划方式都先写入未确认草稿，再共用预览。手动填写无需网络或接口设置。
@Composable
private fun AssistantEmpty(work: Workspace, engine: AssistantEngine, select: (Long) -> Unit,
    day: Long, showSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val batches by remember { work.dao.batches(plannerAgentId) }.collectAsState(initial = emptyList())
    val drafts = remember { context.getSharedPreferences("assistant_input", android.content.Context.MODE_PRIVATE) }
    val inputKey = "input-$plannerAgentId"
    var input by remember { mutableStateOf(drafts.getString(inputKey, "").orEmpty()) }
    var screen by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<AgendaBatch?>(null) }

    // 手动和 AI 只在草稿持久化后打开预览，退出预览仍可从收件箱找回。
    fun createDraft(make: suspend () -> AgendaBatch, generated: Boolean = false) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                val batch = make()
                work.saveDraft(batch)
                if (generated) {
                    input = ""
                    drafts.edit { remove(inputKey) }
                }
                screen = ""
                preview = batch
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = assistantError(failure) }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StepSettingsButton(showSettings)
            StepRoundAction(StepGlyph.Draft, "草稿与运行记录", outline = stepMuted, ink = stepMuted) { screen = "inbox" }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("暂无智能体", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { error = ""; screen = "create" }, shape = stepControlShape) { Text("新增智能体") }
            StepGroup {
                Column {
                    SettingLine(StepGlyph.Agent, "AI 规划") { error = ""; screen = "ai" }
                    HorizontalDivider(color = stepLine, modifier = Modifier.padding(horizontal = stepInset))
                    SettingLine(StepGlyph.Agenda, "手动填写") { error = ""; screen = "manual" }
                }
            }
            if (error.isNotBlank() && screen != "ai") Text(error, color = stepRed)
        }
    }
    when (screen) {
        "create" -> ProfileDialog(AssistantProfile(name = ""), { if (!busy) screen = "" }) { profile ->
            if (!busy) {
                busy = true
                scope.launch {
                    try { select(work.dao.add(profile)); screen = "" }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = assistantError(failure); screen = "" }
                    finally { busy = false }
                }
            }
        }
        "ai" -> FormDialog("日程规划", { if (!busy) screen = "" }, actions = {
            Button(enabled = !busy && input.isNotBlank(), shape = stepControlShape, onClick = {
                createDraft({ engine.makeAgenda(AssistantProfile(name = "日程规划"), input.trim(), day,
                    confirm = { _, _ -> false }) }, generated = true)
            }) { Text("生成预览") }
        }) {
            Field(input, { value -> input = value; drafts.edit { putString(inputKey, value) } }, "输入计划", lines = 4)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = stepRed)
        }
        "manual" -> CalendarItemEditor(remember(day) { CalendarWire.newEvent(day) }, { if (!busy) screen = "" },
            saveLabel = "预览", save = { row ->
                createDraft({ AgendaBatch(manualDraftPrefix + UUID.randomUUID(), plannerAgentId, CalendarWire.encode(listOf(row))) })
            })
        "inbox" -> AssistantInbox(batches, emptyList(), { screen = "" }) { batch -> screen = ""; preview = batch }
    }
    preview?.let { batch -> key(batch.id) { CalendarPreview(batch, work) { preview = null } } }
}

// 按可用高度展开助手列表。点按切换，长按配置。极矮窗口在列表下方保留记忆入口。
@Composable
private fun AssistantPicker(expanded: Boolean, agents: List<AssistantProfile>, active: Long, maxHeight: Dp,
    close: () -> Unit, choose: (Long) -> Unit, edit: (Long) -> Unit,
    quickMemory: (() -> Unit)?) {
    StepAnchoredPanel(expanded, close, modifier = Modifier.width(176.dp).heightIn(max = maxHeight)) {
        Column(Modifier.padding(stepRoundInset)) {
            // 列表短时按内容收紧。助手较多时滚动列表，便于触达后面的选项。
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                agents.forEach { other ->
                    StepMenuPill(other.name, Modifier.fillMaxWidth(), stretch = true, active = other.id == active,
                        onLongClickLabel = "配置智能体：${other.name}", onLongClick = { edit(other.id) }) { choose(other.id) }
                }
            }
            if (quickMemory != null) MemoryPill(Modifier.fillMaxWidth(), onClick = quickMemory)
        }
    }
}

// 18 dp 灯泡与灰字共用 34 dp 高的胶囊，胶囊之外保留 48 dp 触控行。
@Composable
private fun MemoryPill(modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = stepIconTouchSize)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.height(stepRoundDiameter), shape = CircleShape, color = stepPaper, border = BorderStroke(1.dp, stepLine)) {
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StepIcon(StepGlyph.Idea, color = stepMuted.copy(alpha = if (enabled) 1f else .65f), diameter = 18.dp)
                Text("存为记忆", color = if (enabled) stepMuted else stepMuted.copy(alpha = .65f),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = TextUnit.Unspecified),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// 模式和发送是 34 dp 圆面，输入框也以同样高度呈胶囊状。三者保持等距。
@Composable
private fun AssistantComposer(input: String, update: (String) -> Unit, planMode: Boolean, switchMode: () -> Unit,
    busy: Boolean, send: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium.copy(lineHeight = TextUnit.Unspecified)
    val modeName = if (planMode) "日程模式" else "聊天模式"
    val placeholder = "$modeName。${if (planMode) "输入计划。" else "输入消息。"}"
    val measure = rememberTextMeasurer()
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        // 一次点按直接切换，当前草稿不删改。读屏会读出当前模式及目标模式。
        StepRoundAction(if (planMode) StepGlyph.Near else StepGlyph.Conversation,
            "$modeName，切换到${if (planMode) "聊天模式" else "日程模式"}", outline = stepAccent,
            enabled = !busy, iconSize = 18.dp, onClick = switchMode)
        Surface(Modifier.weight(1f).height(stepRoundDiameter), shape = CircleShape, color = stepCell,
            border = BorderStroke(1.dp, stepLine)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                // 窄屏只显示模式名，完整提示仍可读屏。已有多行文字可以随光标滚动，底层草稿不截断。
                val fullHintFits = with(LocalDensity.current) { measure.measure(placeholder, style = style).size.width <= maxWidth.roundToPx() }
                BasicTextField(input, update, enabled = !busy, textStyle = style.copy(color = stepInk),
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = placeholder },
                    decorationBox = { field ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (input.isEmpty()) Text(if (fullHintFits) placeholder else modeName,
                                color = stepMuted, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                    })
            }
        }
        StepRoundAction(StepGlyph.SendUp, "发送", fill = stepAccent, ink = Color.White,
            enabled = !busy && input.isNotBlank(), onClick = send)
    }
}

// 预览容器先保存编辑，再由 Room 事务落盘。丢弃草稿和取消查看是不同入口。
@Composable
private fun CalendarPreview(batch: AgendaBatch, work: Workspace, close: () -> Unit) {
    val rows = remember { mutableStateListOf<CalendarEntry>().apply { addAll(CalendarWire.parse(batch.items, allowEmpty = true)) } }
    val scope = rememberCoroutineScope()
    var edit by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf("") }
    var waiting by remember { mutableStateOf(false) }
    // 编辑和移除先更新 Room，再更新预览。返回列表或重开应用都读同一份草稿。
    fun persist(change: suspend () -> Unit) {
        if (waiting) return
        waiting = true
        error = ""
        scope.launch {
            try { change() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { error = assistantError(e); edit = -1 }
            finally { waiting = false }
        }
    }
    fun revise(updated: List<CalendarEntry>) = persist {
        check(work.dao.revise(batch.id, CalendarWire.encode(updated)) == 1) { "草稿已处理，请重新打开列表" }
        rows.clear()
        rows.addAll(updated)
        edit = -1
    }
    FormDialog("确认日程草稿", close = { if (!waiting) close() }, actions = {
        // 底部固定操作只用图标。退出预览与丢弃分别有自己的入口。
        StepRoundAction(StepGlyph.Trash, "丢弃草稿", ink = stepRed, outline = stepRed, enabled = !waiting) {
            persist { work.dao.discard(batch.id); close() }
        }
        StepRoundAction(StepGlyph.Check, "确认保存", fill = stepAccent, ink = Color.White,
            enabled = !waiting && rows.isNotEmpty()) {
            persist { work.accept(batch.id, rows.toList()); close() }
        }
    }) {
        rows.forEachIndexed { index, item ->
            AgendaDraftCard(item, enabled = !waiting, edit = { edit = index },
                remove = { revise(rows.toMutableList().apply { removeAt(index) }) })
        }
        if (rows.isEmpty()) Text("草稿还没有事项。")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    rows.getOrNull(edit)?.let { original ->
        key(edit) {
            CalendarItemEditor(original, close = { if (!waiting) edit = -1 }) { item ->
                revise(rows.toMutableList().apply { set(edit, item) })
            }
        }
    }
}

// 草稿卡以类别色标记正文。色条只随标题、时段和备注增高，编辑操作另占一行。
@Composable
private fun AgendaDraftCard(item: CalendarEntry, enabled: Boolean, edit: () -> Unit, remove: () -> Unit) {
    val palette = calendarPalette(item.kind)
    Surface(Modifier.fillMaxWidth(), shape = stepFormGroup, color = Color.White, contentColor = stepInk) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize()) { CalendarStripe(palette.stripe) }
                // Markdown 保留原文格式，日期与时间用较浅颜色显示。
                Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, style = stepItemTitle, color = palette.ink)
                    Text("${LocalDate.ofEpochDay(item.day)} · ${eventTime(item)}${if (item.place.isBlank()) "" else " · ${item.place}"}",
                        style = MaterialTheme.typography.bodySmall, color = stepInk.copy(alpha = .70f))
                    if (item.note.isNotBlank()) ProvideTextStyle(MaterialTheme.typography.bodyMedium) { ChatMarkdown(item.note) }
                }
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(start = 4.dp)) {
                TextButton(enabled = enabled, onClick = edit) { Text("编辑") }
                TextButton(enabled = enabled, onClick = remove) { Text("移除") }
            }
        }
    }
}

// 手动新增、已保存事项和 AI 草稿共用一个表单。本地日程带起止时间，待办只需要日期。
@Composable
internal fun CalendarItemEditor(item: CalendarEntry, close: () -> Unit,
    remove: (() -> Unit)? = null, saveLabel: String = "保存", save: (CalendarEntry) -> Unit) {
    val context = LocalContext.current
    // 起止时刻保留完整日期，系统重建窗口后仍能恢复刚填写的标题、时间和备注。
    var title by rememberSaveable { mutableStateOf(item.title) }
    var day by rememberSaveable { mutableStateOf(LocalDate.ofEpochDay(item.day)) }
    var timed by rememberSaveable { mutableStateOf(item.start != null) }
    var start by rememberSaveable { mutableStateOf(item.start?.let(CalendarWire::time) ?: day.atTime(9, 0)) }
    var end by rememberSaveable { mutableStateOf(item.end?.let(CalendarWire::time) ?: start.plusHours(1)) }
    var place by rememberSaveable { mutableStateOf(item.place) }
    var note by rememberSaveable { mutableStateOf(item.note) }
    var type by rememberSaveable { mutableStateOf(item.kind) }
    val titleLabel = (if (item.title.isBlank()) "新增" else "编辑") + (if (timed) "日程" else "待办")
    val timeIssue = when {
        !timed -> ""
        end <= start -> "结束时间须晚于开始时间。跨午夜请调整结束日期。"
        end > start.plusDays(7) -> "单项日程最长可安排七天。"
        else -> ""
    }

    // 使用手机的日期与 24 小时时钟选择器。回调只在用户点确定时更新表单。
    fun pickDate(value: LocalDate, apply: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, y, m, d -> apply(LocalDate.of(y, m + 1, d)) },
            value.year, value.monthValue - 1, value.dayOfMonth).show()
    }
    fun pickTime(value: LocalTime, apply: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, hour, minute -> apply(LocalTime.of(hour, minute)) },
            value.hour, value.minute, true).show()
    }

    // 改日期时平移起止日期，保留原时长与跨午夜关系。切成待办时也保存这些草稿值。
    fun changeDay(picked: LocalDate) {
        val shift = picked.toEpochDay() - day.toEpochDay()
        start = start.plusDays(shift)
        end = end.plusDays(shift)
        day = picked
    }
    val palette = calendarPalette(type)
    FormDialog(titleLabel, close, containerColor = stepFormPaper, shape = stepFormPanel, actions = {
        if (remove != null) OutlinedButton(onClick = remove, shape = stepControlShape) { Text("删除", color = stepRed) }
        Button(shape = stepControlShape, enabled = title.isNotBlank() && timeIssue.isEmpty(), onClick = {
            // 手动保存和 AI 草稿保留同样的字段：编号和批次保持关联，只有待办可处于完成状态。
            val entry = CalendarWire.edit(title, day.toString(), if (timed) start.toString() else "",
                if (timed) end.toString() else "", if (timed) place else "", type, note)
            save(entry.copy(id = item.id, done = item.done && !timed, batchId = item.batchId))
        }) { Text(saveLabel) }
    }) {
        // 两个等宽按钮提供可读的选中状态，新建与编辑都可切换。
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = timed, onClick = { timed = true },
                label = { Text("日程", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f), shape = CircleShape, colors = calendarChipColors())
            FilterChip(selected = !timed, onClick = { timed = false },
                label = { Text("待办", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f), shape = CircleShape, colors = calendarChipColors())
        }
        // 每组字段共用白底。标题组的色条跟随当前日程类别，待办使用提醒色。
        CalendarFormGroup(stripe = if (timed) palette.stripe else stepRed) {
            Field(title, { title = it }, "标题")
        }
        CalendarFormGroup {
            if (timed) {
                CalendarTimeRow("开始", start.toLocalDate(), start.toLocalTime(),
                    { pickDate(day, ::changeDay) }, {
                        pickTime(start.toLocalTime()) { picked ->
                            // 起始时刻改变时带着结束时刻一起移动，原有时长和跨日行为照常。
                            val shift = picked.toSecondOfDay() - start.toLocalTime().toSecondOfDay()
                            start = day.atTime(picked)
                            end = end.plusSeconds(shift.toLong())
                        }
                    })
                HorizontalDivider(color = stepLine)
                CalendarTimeRow("结束", end.toLocalDate(), end.toLocalTime(),
                    { pickDate(end.toLocalDate()) { end = it.atTime(end.toLocalTime()) } },
                    { pickTime(end.toLocalTime()) { end = end.toLocalDate().atTime(it) } })
            } else CalendarTimeRow("日期", day, null, { pickDate(day, ::changeDay) })
        }
        if (timeIssue.isNotEmpty()) Text(timeIssue, color = MaterialTheme.colorScheme.error)

        // 地点和类别只对定时日程显示；暂时切成待办时，表单仍保留未保存的值。
        CalendarFormGroup {
            if (timed) {
                Text("地点", style = MaterialTheme.typography.titleMedium)
                Field(place, { place = it }, "地点（选填）", showLabel = false)
                CalendarTypeChoices(type, { type = it })
            }
            Text("备注", style = MaterialTheme.typography.titleMedium)
            Field(note, { note = it }, "备注（选填）", lines = 2,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = stepMuted), showLabel = false)
        }
    }
}

// 色条和内容分别摆放。matchParentSize 让色条随表单组自然增高，不抢占布局高度。
@Composable
private fun CalendarFormGroup(stripe: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = stepFormGroup, color = Color.White, contentColor = stepInk) {
        Box {
            if (stripe != null) Box(Modifier.matchParentSize().padding(start = 8.dp, top = 14.dp, bottom = 14.dp)) {
                CalendarStripe(stripe)
            }
            Column(Modifier.fillMaxWidth().padding(start = if (stripe == null) 12.dp else 20.dp,
                top = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

// 日期和时刻独立点按。FlowRow 在窄屏或字体放大后允许第二个按钮换行。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTimeRow(label: String, date: LocalDate, time: LocalTime?, editDate: () -> Unit,
    editTime: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val narrow = maxWidth < 244.dp && LocalDensity.current.fontScale >= 1.5f
        val values: @Composable (Modifier) -> Unit = { modifier ->
            FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                val dateLabel = if (time == null) label else "${label}日期"
                CalendarValue(date.toString(), dateLabel, editDate)
                if (time != null) CalendarValue(time.toString(), "${label}时间", editTime)
            }
        }
        // 窄屏把区名独立为一行，优先保留日期及时间胶囊的全文。
        if (narrow) Column {
            Text(label, style = MaterialTheme.typography.titleMedium)
            values(Modifier.fillMaxWidth())
        } else androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, softWrap = false)
            values(Modifier.weight(1f))
        }
    }
}

// 胶囊内只显示值，辅助功能仍能读出这是开始或结束日期／时间。
@Composable
private fun CalendarValue(value: String, label: String, click: () -> Unit) {
    TextButton(onClick = click, shape = CircleShape,
        modifier = Modifier.semantics { contentDescription = "$label · $value" },
        colors = ButtonDefaults.textButtonColors(containerColor = stepFormPaper, contentColor = stepInk),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

// 类别可在换行后继续选择。圆点与清单及时间轴的色条共用颜色。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTypeChoices(type: String, change: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CalendarWire.kinds.forEach { (code, name) ->
            FilterChip(shape = CircleShape, colors = calendarChipColors(), selected = code == type, onClick = { change(code) },
                leadingIcon = { Box(Modifier.size(8.dp).background(calendarPalette(code).stripe, CircleShape)) },
                label = { Text(name) })
        }
    }
}

// 表单统一采用可滚动正文与固定操作区，键盘出现时仍能找到保存按钮。
@Composable
internal fun FormDialog(title: String, close: () -> Unit, actions: @Composable RowScope.() -> Unit,
    containerColor: Color = stepPaper, shape: androidx.compose.ui.graphics.Shape = stepDialogShape, body: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = shape, color = containerColor,
            modifier = Modifier.padding(14.dp).widthIn(max = 550.dp).fillMaxWidth().heightIn(max = 760.dp).imePadding()) {
            Column(Modifier.padding(18.dp)) {
                SectionTitle(title, close)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = body)
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}

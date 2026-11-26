package com.hannah.stepwise

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// 设置首页一张卡片含五个平级入口，通知有独立卡片。子页共享圆形返回。
@Composable
internal fun SettingsPage(work: Workspace, agents: List<AssistantProfile>, route: String, leave: () -> Unit,
    go: (String) -> Unit, select: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by remember { work.dao.jobs() }.collectAsState(initial = emptyList())
    var memory by remember { mutableStateOf<AssistantProfile?>(null) }
    var mcp by remember { mutableStateOf<AssistantProfile?>(null) }
    var jobAgent by remember { mutableStateOf<AssistantProfile?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { Reminders.check(context) }
    }

    fun requestNotifications() {
        if (!Reminders.allowed(context) && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    when (route) {
        "api" -> ApiScreen { go("") }
        "skills" -> SkillsScreen(agents) { go("tools") }
        "notice" -> NotificationSettings(goBack = { go("") })
        "agents" -> AssistantManagement(work, agents, { go("") }, select)
        "tools" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("配置") { go("") }
            StepGroup {
                Column {
                    SettingLine(StepGlyph.Memory, "Agent Skills") { go("skills") }
                    HorizontalDivider(color = stepLine)
                    SettingLine(StepGlyph.Tools, "MCP 工具") { go("mcp") }
                }
            }
        }
        "" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("设置", leave)
            Text("AI 配置", color = stepMuted, style = MaterialTheme.typography.labelLarge)
            StepGroup {
                Column {
                    val entries = listOf(Triple(StepGlyph.Api, "API 配置", "api"),
                        Triple(StepGlyph.Agent, "智能体", "agents"), Triple(StepGlyph.Memory, "记忆", "memory"),
                        Triple(StepGlyph.Tools, "配置", "tools"), Triple(StepGlyph.Clock, "定时任务", "jobs"))
                    entries.forEachIndexed { index, (icon, title, page) ->
                        if (index > 0) HorizontalDivider(color = stepLine, modifier = Modifier.padding(horizontal = stepInset))
                        SettingLine(icon, title) { go(page) }
                    }
                }
            }
            StepGroup { SettingLine(StepGlyph.Bell, "通知") { go("notice") } }
        }
        else -> Column(Modifier.fillMaxSize()) {
            val title = when (route) {
                "memory" -> "记忆"
                "jobs" -> "定时任务"
                else -> "MCP 工具"
            }
            val icon = when (route) {
                "memory" -> StepGlyph.Memory
                "jobs" -> StepGlyph.Clock
                else -> StepGlyph.Tools
            }
            SectionTitle(title) { go(if (route == "mcp") "tools" else "") }
            LazyColumn(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(agents, key = { it.id }) { agent ->
                    StepGroup {
                        SettingLine(icon, agent.name) {
                            when (route) {
                                "memory" -> memory = agent
                                "jobs" -> jobAgent = agent
                                else -> mcp = agent
                            }
                        }
                    }
                }
            }
        }
    }

    memory?.let { agent -> MemoryDialog(work, agent, close = { memory = null }) }
    mcp?.let { agent -> McpDialog(work, agent, close = { mcp = null }) }
    jobAgent?.let { agent ->
        JobsDialog(work, agent, jobs.filter { it.agentId == agent.id }, { jobAgent = null }, ::requestNotifications)
    }
}

// 底部两端是单独的操作区。删除经历进入选择、选择一位、确认三步，由 Workspace 清理关联数据。
@Composable
private fun AssistantManagement(work: Workspace, agents: List<AssistantProfile>, back: () -> Unit, select: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<AssistantProfile?>(null) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableLongStateOf(0L) }
    var confirmation by remember { mutableStateOf<AssistantProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val chosen = agents.firstOrNull { it.id == target }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("智能体") {
            if (selecting) { selecting = false; target = 0L } else back()
        }
        if (selecting) Text("选择一位智能体，再点删除", Modifier.padding(horizontal = stepInset), color = stepMuted)
        if (error.isNotBlank()) Text(error, color = stepRed)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (agents.isEmpty()) item { Text("暂无智能体", Modifier.padding(horizontal = stepInset), color = stepMuted) }
            items(agents, key = { it.id }) { agent ->
                StepGroup {
                    if (selecting) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { target = agent.id }
                            .heightIn(min = 50.dp).padding(horizontal = stepInset),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = agent.id == target, onClick = null)
                            Text(agent.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        }
                    } else SettingLine(StepGlyph.Agent, agent.name) { profile = agent }
                }
            }
        }
        AssistantActionRow(
            left = {
                StepRoundAction(StepGlyph.Trash, "删除智能体", fill = stepRed, ink = Color.White,
                    enabled = !busy && agents.isNotEmpty() && (!selecting || chosen != null)) {
                    if (selecting) confirmation = chosen else { selecting = true; target = 0L; error = "" }
                }
            },
            right = {
                StepRoundAction(StepGlyph.Add, "新增智能体", fill = stepAccent, ink = Color.White, enabled = !busy) {
                    selecting = false
                    target = 0L
                    profile = AssistantProfile(name = "")
                }
            }
        )
    }
    profile?.let { value ->
        ProfileDialog(value, close = { if (!busy) profile = null }) { updated ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        if (updated.id == 0L) select(work.dao.add(updated)) else work.dao.update(updated)
                        profile = null
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = assistantError(failure); profile = null }
                    finally { busy = false }
                }
            }
        }
    }
    confirmation?.let { agent ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, shape = stepDialogShape,
            title = { Text("删除「${agent.name}」？") },
            text = { Text("将删除聊天、记忆、未发送内容、未确认草稿、MCP 连接与令牌、定时任务及运行记录。已保存的日程、待办和批次会保留。") },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("取消") } },
            confirmButton = {
                Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = stepRed),
                    shape = stepControlShape, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                work.deleteAssistant(agent.id)
                                selecting = false
                                target = 0L
                                confirmation = null
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { error = assistantError(failure); confirmation = null }
                            finally { busy = false }
                        }
                    }) { Text("确认删除") }
            })
    }
}

// 用外移的触控留白抵消圆面内缩，两个 34 dp 圆面正好贴到列表或上方胶囊的边线。
@Composable
internal fun AssistantActionRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.offset(x = -stepRoundInset)) { left() }
        Box(Modifier.offset(x = stepRoundInset)) { right() }
    }
}

// 独立检查 App 权限、待办通知类别与精确闹钟权限，测试按钮不消耗当天提醒。
@Composable
private fun NotificationSettings(goBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reminder by remember { mutableStateOf(Reminders.read(context)) }
    var appAllowed by remember { mutableStateOf(Reminders.allowed(context)) }
    var channelAllowed by remember { mutableStateOf(Reminders.channel(context).importance > 0) }
    var exact by remember { mutableStateOf(Reminders.exact(context)) }
    var testMessage by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    fun refresh() {
        appAllowed = Reminders.allowed(context)
        channelAllowed = Reminders.channel(context).importance > 0
        exact = Reminders.exact(context)
        Reminders.schedule(context)
        scope.launch { Reminders.check(context) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
        if (testing) testMessage = if (Reminders.test(context)) "已发送测试通知" else "系统通知未开启"
        testing = false
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, onEvent = { refresh() })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("通知", goBack)
        StepGroup {
            Column(Modifier.padding(stepInset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("每日待办提醒", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = reminder.enabled, onCheckedChange = {
                        reminder = reminder.copy(enabled = it)
                        Reminders.save(context, reminder)
                        refresh()
                        if (it && !appAllowed && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        reminder = reminder.copy(hour = hour, minute = minute)
                        Reminders.save(context, reminder)
                        refresh()
                    }, reminder.hour, reminder.minute, true).show()
                }) { Text("提醒时间  %02d:%02d".format(reminder.hour, reminder.minute)) }
                Text("系统通知  ${if (appAllowed) "已开启" else "已关闭"}")
                Text("每日待办类别  ${if (channelAllowed) "已开启" else "已关闭"}")
                Text("闹钟排程  ${if (exact) "精确" else "系统可延迟"}")
                TextButton(onClick = {
                    val action = if (appAllowed) android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    else android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    context.startActivity(Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Reminders.channel))
                }) { Text("系统通知设置") }
                if (!exact && Build.VERSION.SDK_INT >= 31) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}"))) }
                        .onFailure { testMessage = "此设备请从系统设置开启闹钟和提醒权限。" }
                }) { Text("开启精确闹钟权限") }
                OutlinedButton(shape = stepControlShape, onClick = {
                    if (!appAllowed && Build.VERSION.SDK_INT >= 33) {
                        testing = true
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else testMessage = if (Reminders.test(context)) "已发送测试通知" else "请检查系统通知及类别"
                }) { Text("测试通知") }
                if (testMessage.isNotBlank()) Text(testMessage)
            }
        }
    }
}

@Composable
internal fun ProfileDialog(profile: AssistantProfile, close: () -> Unit, save: (AssistantProfile) -> Unit) {
    val context = LocalContext.current
    val deepSeek = remember { runCatching { Ai.endpoint(Vault(context).read().base).host == "api.deepseek.com" }.getOrDefault(false) }
    var name by remember { mutableStateOf(profile.name) }
    var model by remember { mutableStateOf(profile.model) }
    var temperature by remember { mutableStateOf(profile.temperature?.toString().orEmpty()) }
    var effort by remember { mutableStateOf(profile.effort) }
    var prompt by remember { mutableStateOf(profile.prompt) }
    var menu by remember { mutableStateOf(false) }
    val options = if (deepSeek) listOf("" to "模型默认", "none" to "关闭思考", "low" to "低", "high" to "高", "max" to "最高")
        else listOf("" to "模型默认", "low" to "低", "medium" to "中", "high" to "高")
    val temp = temperature.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    val temperatureEnabled = !deepSeek || effort == "none"
    FormDialog(if (profile.id == 0L) "创建智能体" else "智能体设定", close, actions = {
        Button(shape = stepControlShape, enabled = name.isNotBlank() && (!temperatureEnabled || temperature.isBlank() || temp != null), onClick = {
            save(profile.copy(name = name.trim(), model = model.trim(), temperature = temp,
                effort = effort, prompt = prompt.trim()))
        }) { Text("保存") }
    }) {
        Field(name, { name = it }, "助手名称")
        Field(model, { model = it }, "模型（留空继承全局）")
        OutlinedTextField(temperature, { temperature = it }, shape = stepControlShape, enabled = temperatureEnabled,
            label = { Text("温度（0–2，可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Box {
            OutlinedButton(shape = stepControlShape, onClick = { menu = true }) { Text("推理强度 · ${options.toMap()[effort] ?: effort}") }
            DropdownMenu(menu, shape = stepControlShape, onDismissRequest = { menu = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { effort = value; menu = false })
                }
            }
        }
        Text(if (deepSeek) "DeepSeek 关闭思考后使用温度设定。" else "选择模型默认可使用服务商原有参数。",
            style = MaterialTheme.typography.bodySmall)
        Field(prompt, { prompt = it }, "系统提示词", lines = 4)
    }
}

@Composable
private fun MemoryDialog(work: Workspace, agent: AssistantProfile, close: () -> Unit) {
    val items by remember(agent.id) { work.dao.memory(agent.id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<AssistantMemory?>(null) }
    FormDialog("${agent.name} · 长期记忆", close, actions = {
        Button(shape = stepControlShape, enabled = draft.isNotBlank(), onClick = {
            val item = editing
            val text = draft.trim()
            scope.launch {
                if (item == null) work.saveMemories(agent.id, listOf(text))
                else work.dao.update(item.copy(text = text))
                draft = ""
                editing = null
            }
        }) { Text(if (editing == null) "新增" else "更新") }
    }) {
        Field(draft, { draft = it }, if (editing == null) "记忆内容" else "编辑记忆", lines = 2)
        items.forEach { item ->
            Text(item.text)
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { editing = item; draft = item.text }) { Text("编辑") }
                TextButton(onClick = {
                    scope.launch { work.dao.delete(item) }
                    if (editing?.id == item.id) { editing = null; draft = "" }
                }) { Text("删除") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun McpDialog(work: Workspace, agent: AssistantProfile, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { Vault(context) }
    val servers by remember(agent.id) { work.dao.servers(agent.id) }.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<McpEndpoint?>(null) }
    FormDialog("${agent.name} · MCP 工具", close, actions = {
        Button(shape = stepControlShape, onClick = { editing = McpEndpoint(UUID.randomUUID().toString(), agent.id, "", "") }) { Text("添加服务") }
    }) {
        if (servers.isEmpty()) Text("填写远程 Streamable HTTP 地址，连接后选择工具。")
        servers.forEach { server ->
            Text(server.name, fontWeight = FontWeight.Bold)
            Text("${server.url} · 启用 ${jsonStrings(server.enabledTools).size} 项", style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { editing = server }) { Text("配置") }
                TextButton(onClick = {
                    scope.launch { work.dao.delete(server); vault.putToken("mcp-${server.id}", "") }
                }) { Text("移除") }
            }
            HorizontalDivider()
        }
    }
    editing?.let { server ->
        key(server.id) {
            McpEditor(work, server) { editing = null }
        }
    }
}

// 发现工具后用指纹保存启用项。地址或令牌改变时，为新连接重新选择工具。
@Composable
private fun McpEditor(work: Workspace, server: McpEndpoint, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember { Vault(context) }
    val previousToken = remember { runCatching { vault.token("mcp-${server.id}") }.getOrDefault("") }
    var name by remember { mutableStateOf(server.name) }
    var url by remember { mutableStateOf(server.url) }
    var token by remember { mutableStateOf(previousToken) }
    var list by remember { mutableStateOf<List<RemoteTool>?>(null) }
    var enabled by remember { mutableStateOf(jsonStrings(server.enabledTools)) }
    var error by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    val replacement = remember { UUID.randomUUID().toString() }
    fun current() = server.copy(id = if (server.url.isNotBlank() && (server.url != url.trim() || previousToken != token.trim()))
        replacement else server.id, name = name.trim(), url = url.trim())

    FormDialog("远程 MCP 服务", close, actions = {
        Button(shape = stepControlShape, enabled = !fetching && url.isNotBlank() && name.isNotBlank(), onClick = {
            scope.launch {
                try {
                    val target = current()
                    secureUrl(target.url)
                    vault.putToken("mcp-${target.id}", token.trim())
                    work.dao.save(target.copy(enabledTools = JSONArray(enabled.toList()).toString()))
                    if (target.id != server.id) { work.dao.delete(server); vault.putToken("mcp-${server.id}", "") }
                    close()
                } catch (e: Exception) { error = assistantError(e) }
            }
        }) { Text("保存") }
    }) {
        Field(name, { name = it }, "服务名称")
        // 地址变化时立即丢弃表单中的旧令牌和授权。取消编辑会保留原连接。
        Field(url, { value ->
            if (!fetching && value != url) {
                if (value.trim() != url.trim()) {
                    token = ""
                    list = null
                    enabled = emptySet()
                    error = ""
                }
                url = value
            }
        }, "HTTPS Streamable HTTP 地址")
        OutlinedTextField(token, { if (!fetching) { token = it; list = null; enabled = emptySet() } }, shape = stepControlShape,
            label = { Text("访问令牌（选填）") }, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(shape = stepControlShape, enabled = !fetching && url.isNotBlank(), onClick = {
            fetching = true
            scope.launch {
                try {
                    val tools = AssistantEngine(context).discover(current(), token.trim())
                    enabled = enabled.intersect(tools.map { it.key }.toSet())
                    list = tools
                    error = if (tools.isEmpty()) "此服务未提供工具" else ""
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { error = assistantError(e) }
                finally { fetching = false }
            }
        }) { Text(if (fetching) "连接中…" else "连接并加载工具") }
        list?.forEach { tool ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                StepCheckbox(tool.key in enabled, { on -> enabled = if (on) enabled + tool.key else enabled - tool.key })
                Column {
                    Text(tool.name + if (tool.readOnly) " · 只读" else "")
                    if (tool.description.isNotBlank()) Text(tool.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun JobsDialog(work: Workspace, agent: AssistantProfile, jobs: List<AssistantJob>,
    close: () -> Unit, notify: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<AssistantJob?>(null) }
    var error by remember { mutableStateOf("") }
    val runner = remember { AssistantJobs(context) }
    FormDialog("${agent.name} · 定时任务", close, actions = {
        Button(shape = stepControlShape, onClick = {
            edit = AssistantJob(agentId = agent.id, name = "", prompt = "",
                first = LocalDateTime.now().plusMinutes(10).truncatedTo(ChronoUnit.MINUTES).toString(),
                repeat = "once", agenda = false, tools = "[]")
        }) { Text("新建定时任务") }
    }) {
        if (jobs.isEmpty()) Text("设置一次、每日或每周的运行时间。手机联网后由系统执行。")
        jobs.forEach { job ->
            Text(job.name, fontWeight = FontWeight.Bold)
            val cadence = mapOf("once" to "单次", "daily" to "每日", "weekly" to "每周")
            Text("${job.first.replace('T', ' ')} · ${cadence[job.repeat]}")
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = job.enabled, onCheckedChange = { enabled ->
                    scope.launch {
                        try {
                            val changed = job.copy(enabled = enabled, revision = job.revision + 1)
                            work.dao.update(changed)
                            runner.replace(changed)
                            if (enabled) notify()
                        } catch (e: Exception) { error = assistantError(e) }
                    }
                })
                TextButton(onClick = { edit = job }) { Text("编辑") }
                TextButton(onClick = { scope.launch { work.dao.delete(job); runner.cancel(job.id) } }) { Text("删除") }
            }
            HorizontalDivider()
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    edit?.let { job ->
        key(job.id) { JobEditor(work, job, close = { edit = null }, notify = notify) }
    }
}

@Composable
private fun JobEditor(work: Workspace, job: AssistantJob, close: () -> Unit, notify: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { AssistantEngine(context) }
    var name by remember { mutableStateOf(job.name) }
    var prompt by remember { mutableStateOf(job.prompt) }
    var date by remember { mutableStateOf(job.first.replace('T', ' ')) }
    var repeat by remember { mutableStateOf(job.repeat) }
    var agenda by remember { mutableStateOf(job.agenda) }
    var tools by remember { mutableStateOf(jsonStrings(job.tools)) }
    var available by remember { mutableStateOf<List<RemoteTool>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    FormDialog("编辑定时任务", close, actions = {
        Button(shape = stepControlShape, enabled = name.isNotBlank() && prompt.isNotBlank() && !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    val first = LocalDateTime.parse(date.trim().replace(' ', 'T')).truncatedTo(ChronoUnit.MINUTES)
                    require(job.id != 0L || !first.isBefore(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))) {
                        "请选未来的运行时间"
                    }
                    var updated = job.copy(name = name.trim(), prompt = prompt.trim(), first = first.toString(),
                        repeat = repeat, agenda = agenda, tools = JSONArray(tools.toList()).toString(), revision = job.revision + 1)
                    if (job.id == 0L) updated = updated.copy(id = work.dao.add(updated)) else work.dao.update(updated)
                    AssistantJobs(context).replace(updated)
                    if (updated.enabled) notify()
                    close()
                } catch (e: Exception) {
                    error = if (e is java.time.format.DateTimeParseException) "时间格式示例 2026-11-24 19:00" else assistantError(e)
                    loading = false
                }
            }
        }) { Text("保存任务") }
    }) {
        Field(name, { name = it }, "任务名称")
        Field(prompt, { prompt = it }, "到点执行的提示词", lines = 3)
        Field(date, { date = it }, "首次运行时间 YYYY-MM-DD HH:mm")
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("once" to "单次", "daily" to "每日", "weekly" to "每周").forEach { (value, title) ->
                FilterChip(shape = stepControlShape, selected = repeat == value, onClick = { repeat = value }, label = { Text(title) })
            }
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            StepCheckbox(agenda, { agenda = it })
            Text("结果作为日程草稿")
        }
        Text("已授权 ${tools.size} 个只读工具")
        OutlinedButton(shape = stepControlShape, enabled = !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    val vault = Vault(context)
                    val discovered = work.dao.connections(job.agentId).flatMap { server ->
                        val enabled = jsonStrings(server.enabledTools)
                        if (enabled.isEmpty()) emptyList() else engine.discover(server, vault.token("mcp-${server.id}"))
                            .filter { it.readOnly && it.key in enabled }
                    }
                    available = discovered
                    tools = tools.intersect(discovered.map { it.key }.toSet())
                    error = if (discovered.isEmpty()) "当前没有已启用的只读 MCP 工具" else ""
                } catch (e: Exception) { error = assistantError(e) }
                finally { loading = false }
            }
        }) { Text("选择后台可用的只读工具") }
        available?.forEach { tool ->
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                StepCheckbox(tool.key in tools, { on -> tools = if (on) tools + tool.key else tools - tool.key })
                Text(tool.label)
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

package com.hannah.stepwise

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// Skill 在库里只保存一份正文，各助手的启用状态通过关联表独立维护。
@Entity(indices = [Index(value = ["name"], unique = true)])
data class AgentSkill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "", val description: String = "", val body: String = "", val source: String = ""
)

@Entity(primaryKeys = ["skillId", "agentId"], indices = [Index("agentId")], foreignKeys = [
    ForeignKey(entity = AgentSkill::class, parentColumns = ["id"], childColumns = ["skillId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AssistantProfile::class, parentColumns = ["id"], childColumns = ["agentId"], onDelete = ForeignKey.CASCADE)
])
data class SkillAssignment(val skillId: Long, val agentId: Long)

@Dao
interface SkillsDao {
    @Query("SELECT * FROM AgentSkill ORDER BY name") fun library(): Flow<List<AgentSkill>>
    @Query("SELECT * FROM SkillAssignment WHERE skillId = :id") fun assigned(id: Long): Flow<List<SkillAssignment>>
    @Query("SELECT * FROM AgentSkill WHERE name = :name LIMIT 1") suspend fun named(name: String): AgentSkill?
    @Query("SELECT AgentSkill.* FROM AgentSkill JOIN SkillAssignment ON AgentSkill.id = SkillAssignment.skillId WHERE agentId = :id ORDER BY name")
    suspend fun enabled(id: Long): List<AgentSkill>
    @Insert suspend fun insert(skill: AgentSkill)
    @Update suspend fun update(skill: AgentSkill)
    @Delete suspend fun remove(skill: AgentSkill)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enable(assignment: SkillAssignment)
    @Delete suspend fun disable(assignment: SkillAssignment)
}

class SkillStore(context: Context) {
    val dao = Store.database(context).skills()

    // 合并已启用的技能说明；这里只返回文本，不触碰设备文件、工具授权或程序执行。
    suspend fun instructions(agentId: Long): String {
        val skills = dao.enabled(agentId)
        return if (skills.isEmpty()) "" else skills.joinToString(
            prefix = "\n\n本助手已启用的 Agent Skills（依照应用的输出格式和工具授权执行）：\n",
            separator = "\n\n"
        ) { skill -> "## ${skill.name}\n${skill.description}\n${skill.body}" }
    }
}

// YAML 头只取规范中的名称、描述。正文保留原文，大小限制同时适用于文件和网络输入。
internal object SkillImport {
    private const val limit = 128 * 1024
    fun fromMarkdown(raw: String, source: String): AgentSkill {
        require(raw.encodeToByteArray().size <= limit) { "SKILL.md 须小于 128 KiB" }
        val lines = raw.removePrefix("\uFEFF").lineSequence().toList()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        require(lines.firstOrNull()?.trim() == "---" && end > 0) { "SKILL.md 需要有 YAML 头" }
        val header = lines.subList(1, end).joinToString("\n")
        val yaml = Load(LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
            .setCodePointLimit(8192).build())
        val meta = yaml.loadFromString(header) as? Map<*, *> ?: error("SKILL.md 头格式有误")
        val name = (meta["name"] as? String).orEmpty()
        val description = (meta["description"] as? String).orEmpty()
        val body = lines.drop(end + 1).joinToString("\n").trim()
        require(name.length in 1..64 && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) &&
            description.isNotBlank() && body.isNotBlank()) { "请检查 Skill 的名称、描述和正文" }
        return AgentSkill(name = name, description = description, body = body, source = source)
    }

    private fun read(stream: InputStream): String {
        val bytes = ByteArray(limit + 1)
        var size = 0
        while (size < bytes.size) {
            val n = stream.read(bytes, size, bytes.size - size)
            if (n < 0) break
            size += n
        }
        require(size <= limit) { "SKILL.md 须小于 128 KiB" }
        return bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
    }

    suspend fun file(context: Context, uri: Uri): AgentSkill = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val filename = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty().ifBlank { "SKILL.md" }
        val raw = resolver.openInputStream(uri)?.use(::read) ?: error("无法打开该文件")
        fromMarkdown(raw, filename)
    }

    suspend fun url(input: String): AgentSkill = withContext(Dispatchers.IO) {
        var url = URL(input.trim())
        repeat(5) {
            require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "请填写公开 HTTPS 原文地址" }
            val connection = url.openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("链接重定向不完整"))
                } else {
                    require(code == 200) { "下载失败（HTTP $code）" }
                    return@withContext fromMarkdown(connection.inputStream.use(::read), input.trim())
                }
            } finally { connection.disconnect() }
        }
        error("链接重定向次数过多")
    }

    fun checked(skill: AgentSkill): AgentSkill {
        val updated = skill.copy(name = skill.name.trim(), description = skill.description.trim(), body = skill.body.trim())
        require(updated.name.isNotBlank() && updated.description.isNotBlank() && updated.body.isNotBlank()) { "请填写名称、描述和说明正文" }
        require(updated.body.encodeToByteArray().size <= limit) { "Skill 正文须小于 128 KiB" }
        return updated
    }
}

// 安装入口始终先打开预览；圆形按钮只负责创建空白 Skill。
@Composable
internal fun SkillsScreen(agents: List<AssistantProfile>, back: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SkillStore(context) }
    val list by remember { store.dao.library() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AgentSkill?>(null) }
    var assigning by remember { mutableStateOf<AgentSkill?>(null) }
    var removing by remember { mutableStateOf<AgentSkill?>(null) }
    var replacement by remember { mutableStateOf<AgentSkill?>(null) }
    var urlPrompt by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun action(run: suspend () -> Unit) {
        if (busy) return
        busy = true
        issue = ""
        scope.launch {
            try { run() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { issue = assistantError(e) }
            finally { busy = false }
        }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action { editing = SkillImport.file(context, uri) }
    }

    Column(Modifier.fillMaxSize()) {
        SectionTitle("Agent Skills", back)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = stepControlShape, enabled = !busy, onClick = { file.launch(arrayOf("*/*")) }) { Text("导入文件") }
            OutlinedButton(shape = stepControlShape, enabled = !busy, onClick = { urlPrompt = true; issue = "" }) { Text("网址安装") }
        }
        if (issue.isNotEmpty() && !urlPrompt && editing == null) Text(issue, color = MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 76.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { item ->
                    StepGroup {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(item.description, style = MaterialTheme.typography.bodySmall)
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TextButton(onClick = { issue = ""; editing = item }) { Text("查看与编辑") }
                                TextButton(onClick = { assigning = item }) { Text("分配助手") }
                                TextButton(onClick = { removing = item }) { Text("删除") }
                            }
                        }
                    }
                }
                if (list.isEmpty()) item { Text("还没有安装 Skill。", Modifier.padding(vertical = 12.dp)) }
            }
            FloatingActionButton(onClick = { issue = ""; editing = AgentSkill() }, shape = CircleShape,
                containerColor = stepAccent, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp)) {
                StepIcon(StepGlyph.Add, "新建 Skill")
            }
        }
    }

    if (urlPrompt) FormDialog("从网址安装 Skill", { if (!busy) urlPrompt = false }, actions = {
        Button(shape = stepControlShape, enabled = address.isNotBlank() && !busy, onClick = {
            action { editing = SkillImport.url(address); urlPrompt = false }
        }) { Text(if (busy) "读取中…" else "预览") }
    }) {
        Field(address, { address = it }, "SKILL.md 的 HTTPS 原文地址")
        if (issue.isNotEmpty()) Text(issue, color = MaterialTheme.colorScheme.error)
    }
    editing?.let { item ->
        key(item) {
            var draft by remember { mutableStateOf(item) }
            FormDialog(if (item.id != 0L) "编辑 Skill" else if (item.source.isBlank()) "新建 Skill" else "安装预览",
                { editing = null }, actions = {
                    Button(shape = stepControlShape, enabled = !busy, onClick = {
                        action {
                            val checked = SkillImport.checked(draft)
                            val other = store.dao.named(checked.name)
                            if (other != null && other.id != checked.id) {
                                require(checked.id == 0L) { "该名称已有一条 Skill，请使用其他名称" }
                                replacement = checked.copy(id = other.id)
                            } else {
                                if (checked.id == 0L) store.dao.insert(checked) else store.dao.update(checked)
                                editing = null
                            }
                        }
                    }) { Text(if (item.id == 0L && item.source.isNotBlank()) "安装" else "保存") }
                }) {
                if (item.source.isNotBlank()) Text(item.source, style = MaterialTheme.typography.bodySmall)
                Field(draft.name, { draft = draft.copy(name = it) }, "名称")
                Field(draft.description, { draft = draft.copy(description = it) }, "描述", lines = 2)
                Field(draft.body, { draft = draft.copy(body = it) }, "正文", lines = 5)
                if (issue.isNotBlank()) Text(issue, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    replacement?.let { item ->
        AlertDialog(shape = stepDialogShape, onDismissRequest = { replacement = null }, title = { Text("替换同名 Skill？") },
            text = { Text("将更新 ${item.name} 的说明，原有助手分配会保留。") },
            confirmButton = { TextButton(onClick = {
                action { store.dao.update(item); replacement = null; editing = null }
            }) { Text("确认替换") } }, dismissButton = { TextButton(onClick = { replacement = null }) { Text("取消") } })
    }
    removing?.let { item ->
        AlertDialog(shape = stepDialogShape, onDismissRequest = { removing = null }, title = { Text("删除 ${item.name}？") },
            text = { Text("删除后，已分配的助手会停止使用此 Skill。") },
            confirmButton = { TextButton(onClick = { action { store.dao.remove(item); removing = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
    assigning?.let { item ->
        val enabled by remember(item.id) { store.dao.assigned(item.id) }.collectAsState(initial = emptyList())
        val ids = enabled.map { it.agentId }.toSet()
        FormDialog(item.name + " · 分配助手", { assigning = null }, actions = {}) {
            agents.forEach { agent ->
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    StepCheckbox(agent.id in ids, { checked ->
                        scope.launch {
                            val link = SkillAssignment(item.id, agent.id)
                            if (checked) store.dao.enable(link) else store.dao.disable(link)
                        }
                    })
                    Text(agent.name)
                }
            }
        }
    }
}

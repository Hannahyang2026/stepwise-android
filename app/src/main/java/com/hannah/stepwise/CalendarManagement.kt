package com.hannah.stepwise

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

// 类型、关键词和批次可以一起筛选。旧批次没有历史来源字段，只比较仍与草稿正文一致的记录。
internal fun filterCalendar(entries: List<CalendarEntry>, kind: String?, keyword: String,
    batch: SavedBatch?, legacyRows: List<CalendarEntry>): List<CalendarEntry> {
    val query = keyword.trim()
    val fingerprints = legacyRows.map { it.copy(id = 0, done = false) }.toSet()
    return entries.filter { row ->
        (kind == null || kind == row.kind) &&
            (row.title.contains(query, ignoreCase = true) || row.note.contains(query, ignoreCase = true)) &&
            when {
                batch == null -> true
                batch.linked -> row.batchId == batch.id
                else -> row.batchId == null && row.copy(id = 0, done = false) in fingerprints
            }
    }
}

// 管理窗口分两步。选取阶段只改勾选状态，复核阶段展示拟删条目的固定快照。
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarManagement(work: Workspace, entries: List<CalendarEntry>,
    close: () -> Unit, deleted: (Int) -> Unit) {
    val batches by remember(work) { work.dao.savedBatches() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var keyword by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var batchId by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    var review by remember { mutableStateOf<List<CalendarEntry>?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 显示批次序号、首项标题与日期，便于区分相同课题的多次生成。
    val labels = remember(batches) {
        batches.associate { batch ->
            val first = runCatching { CalendarWire.parse(batch.items, allowEmpty = true).firstOrNull() }.getOrNull()
            val name = first?.let { "${it.title.take(22)} · ${LocalDate.ofEpochDay(it.day)}" }.orEmpty()
            batch.id to "${if (batch.linked) "批次" else "旧批次"} ${batch.sequence} · $name".trimEnd(' ', '·')
        }
    }
    val batch = batches.firstOrNull { it.id == batchId }
    val legacy = batch?.linked == false
    val legacyRows = remember(batch) {
        if (batch == null || batch.linked) emptyList()
        else runCatching { CalendarWire.parse(batch.items, allowEmpty = true) }.getOrDefault(emptyList())
    }
    val matching = remember(entries, kind, keyword, batchId, batch, legacyRows) {
        if (batchId != null && batch == null) emptyList()
        else filterCalendar(entries, kind, keyword, batch, legacyRows)
    }
    val chosen = remember(matching, selection) { matching.filter { it.id in selection } }
    val reviewed = review

    // 筛选改变就重新勾选，避免看不到的选项留在删除清单里。
    fun resetSelection() {
        selection = emptySet()
        error = ""
    }
    fun back() {
        if (!saving) {
            if (review == null) close() else review = null
        }
    }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnClickOutside = false)) {
        Surface(shape = stepDialogShape, color = stepPaper, modifier = Modifier.padding(14.dp)
            .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 710.dp).fillMaxHeight().imePadding()) {
            Column(Modifier.padding(horizontal = stepInset, vertical = 8.dp)) {
                SectionTitle(if (reviewed == null) "批量管理日程" else "核对删除清单", ::back)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (reviewed == null) {
                        item("filters") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(keyword, { keyword = it; resetSelection() }, shape = stepControlShape, singleLine = true,
                                    label = { Text("课题关键词（标题或备注）") }, modifier = Modifier.fillMaxWidth())
                                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CalendarFilterMenu("类型 · ${CalendarWire.kinds[kind] ?: "全部"}", Modifier.weight(1f),
                                        listOf(null to "全部类型") + CalendarWire.kinds.toList()) { kind = it; resetSelection() }
                                    CalendarFilterMenu("AI 批次 · ${batch?.let { it.sequence } ?: "全部"}", Modifier.weight(1f),
                                        listOf(null to "全部批次") + labels.toList()) { batchId = it; resetSelection() }
                                }
                                if (batch != null) Text(labels.getValue(batch.id), style = MaterialTheme.typography.bodySmall)
                                if (legacy) Text("旧版未记录日程来源。以下是内容匹配的候选，请逐项核对勾选。",
                                    color = stepRed, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (matching.isEmpty()) item { Text("没有符合条件的日程", Modifier.padding(vertical = 10.dp)) }
                        items(matching, key = { it.id }) { row ->
                            StepGroup {
                                // 整行可点按，复选框与整行共用一个无障碍选择状态。
                                val checked = row.id in selection
                                androidx.compose.foundation.layout.Row(
                                    Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = {
                                        selection = if (it) selection + row.id else selection - row.id
                                    }).padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StepCheckbox(checked, onCheckedChange = null)
                                    CalendarManagementRow(row)
                                }
                            }
                        }
                    } else {
                        item("count") { Text("即将删除 ${reviewed.size} 项日程。请核对标题、日期和时间。") }
                        items(reviewed, key = { it.id }) { row ->
                            StepGroup {
                                Column(Modifier.padding(12.dp)) {
                                    CalendarManagementRow(row)
                                    if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                HorizontalDivider(color = stepLine)
                // 大字号时操作可换行，确认键不会因同一行太窄而被挤到屏幕外。
                FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (reviewed == null) {
                        Text("已选 ${chosen.size} 项", Modifier.align(Alignment.CenterVertically))
                        // 旧版候选存在同名同时间的手动记录，因此逐条勾选。
                        if (chosen.isNotEmpty() && (legacy || chosen.size == matching.size)) {
                            TextButton(onClick = { selection = emptySet() }) { Text("清空") }
                        } else if (!legacy) {
                            TextButton(enabled = matching.isNotEmpty(), onClick = {
                                selection = matching.map { it.id }.toSet()
                            }) { Text("全选结果") }
                        }
                        Button(shape = stepControlShape, enabled = chosen.isNotEmpty(), onClick = {
                            focus.clearFocus()
                            review = chosen.toList()
                        }) { Text("核对删除") }
                    } else {
                        TextButton(enabled = !saving, onClick = ::back) { Text("返回勾选") }
                        Button(shape = stepControlShape, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = stepRed),
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        deleted(work.deleteReviewed(reviewed))
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        error = assistantError(failure)
                                        review = null
                                        selection = emptySet()
                                    } finally {
                                        saving = false
                                    }
                                }
                            }) { Text("确认删除 ${reviewed.size} 项") }
                    }
                }
            }
        }
    }
}

// 类型与批次使用同样的下拉控件，菜单对长列表提供自身滚动。
@Composable
private fun CalendarFilterMenu(label: String, modifier: Modifier, choices: List<Pair<String?, String>>,
    select: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(shape = stepControlShape, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label ▾") }
        DropdownMenu(expanded, shape = stepControlShape, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; select(value) })
            }
        }
    }
}

// 跨午夜事项展示完整结束日期，核对时仍是一条实际数据库记录。
@Composable
private fun CalendarManagementRow(item: CalendarEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(item.title, fontWeight = FontWeight.Bold)
        val timing = if (item.start != null && item.end != null)
            "${CalendarWire.time(item.start).toString().replace('T', ' ')} – ${CalendarWire.time(item.end).toString().replace('T', ' ')}"
        else "${LocalDate.ofEpochDay(item.day)} · 全天"
        val type = CalendarWire.kinds[item.kind].orEmpty()
        val place = if (item.place.isBlank()) "" else " · ${item.place}"
        Text("$timing · $type$place", color = stepMuted, style = MaterialTheme.typography.bodySmall)
    }
}

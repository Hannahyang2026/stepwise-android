package com.hannah.stepwise

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.URL

// SSE（服务器发送事件）按空行分隔消息。工具的名称、编号和参数可能分散在多个事件中。
private class StreamToolCall {
    val id = StringBuilder()
    val name = StringBuilder()
    val arguments = StringBuilder()

    fun append(part: JSONObject) {
        id.append(part.text("id"))
        val function = part.optJSONObject("function") ?: return
        name.append(function.text("name"))
        arguments.append(function.text("arguments"))
    }

    fun assemble() = JSONObject().put("id", id.toString()).put("type", "function")
        .put("function", JSONObject().put("name", name.toString()).put("arguments", arguments.toString()))
}

// JSON 的 null 不是四个字母组成的字符串。缺失字段和 null 都表示此块没有新增文字。
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

// 输入 Chat Completions 流，边读边发送当前正文。最后拼回与普通响应相同的结构，复用工具确认和入库逻辑。
internal suspend fun readChatStream(reader: BufferedReader, onText: suspend (String) -> Unit): JSONObject {
    val event = StringBuilder()
    val content = StringBuilder()
    val reasoning = StringBuilder()
    val tools = sortedMapOf<Int, StreamToolCall>()
    var received = 0
    var finish = ""
    var lastPaint = 0L

    suspend fun consume(): Boolean {
        val payload = event.toString().trim()
        event.clear()
        if (payload.isEmpty()) return false
        if (payload == "[DONE]") return true
        val data = JSONObject(payload)
        check(!data.has("error")) { "模型在流式输出时返回错误" }
        val choice = data.optJSONArray("choices")?.optJSONObject(0) ?: return false
        choice.text("finish_reason").takeIf(String::isNotBlank)?.let { finish = it }
        val delta = choice.optJSONObject("delta") ?: return false
        val chunk = delta.text("content")
        content.append(chunk)
        // 推理正文仅给服务商在工具轮次中接续使用，界面只展示回复正文。
        reasoning.append(delta.text("reasoning_content"))
        delta.optJSONArray("tool_calls")?.objects()?.forEach { part ->
            tools.getOrPut(part.optInt("index", 0), ::StreamToolCall).append(part)
        }
        val now = System.nanoTime()
        if (chunk.isNotEmpty() && (lastPaint == 0L || now - lastPaint >= 55_000_000L)) {
            onText(content.toString())
            lastPaint = now
        }
        return false
    }

    // 每次读取先检查协程是否仍有效，并限制整次回复体积，避免超长流耗尽手机内存。
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = reader.readLine()
        if (line == null) {
            consume()
            break
        }
        received += line.length + 1
        require(received <= 2_000_000) { "流式回复内容过长" }
        if (line.startsWith("data:")) event.appendLine(line.substring(5).removePrefix(" "))
        if (line.isEmpty() && consume()) break
    }
    check(finish.isNotEmpty()) { "流式连接在回复完成前断开" }
    if (content.isNotEmpty()) onText(content.toString())
    val message = AssistantWire.message("assistant", content.toString())
    if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
    if (tools.isNotEmpty()) message.put("tool_calls", JSONArray(tools.values.map { it.assemble() }))
    return JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", finish)))
}

// 前台聊天请求启用 SSE。兼容仍返回普通 JSON 的同类服务，其回复一次显示。
internal suspend fun exchangeChatStream(url: URL, token: String, body: JSONObject,
    onText: suspend (String) -> Unit): Reply {
    val connection = openPost(url, token, mapOf("Accept" to "text/event-stream, application/json"))
    try {
        connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
        val code = connection.responseCode
        val result = if (code == 200) connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val type = connection.contentType.orEmpty()
            if (type.contains("text/event-stream", ignoreCase = true)) readChatStream(reader, onText)
            else readReply(reader, type, null)
        } else null
        return Reply(code, result, null)
    } finally {
        connection.disconnect()
    }
}

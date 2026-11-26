package com.hannah.stepwise

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatStreamTest {
    // 模拟服务分多次写出 Markdown，正文回调应在流结束前拿到第一段。
    @Test fun publishesMarkdownBeforeFinalChunk() = runBlocking {
        val input = """
            : keepalive
            data: {"choices":[{"delta":{"content":"## 标题","reasoning_content":"hidden"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"\n\n**正文**"},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()
        val updates = mutableListOf<String>()
        val reply = readChatStream(input.reader().buffered()) { updates.add(it) }
        assertEquals("## 标题", updates.first())
        assertEquals("## 标题\n\n**正文**", updates.last())
        assertTrue(updates.none { "hidden" in it })
        assertEquals("stop", reply.getJSONArray("choices").getJSONObject(0).getString("finish_reason"))
    }

    // 拼接同一个工具的参数片段，验证工具名称与参数保持原样。
    @Test fun joinsToolArgumentsAndRejectsBrokenStreams() = runBlocking {
        val start = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"tool-id","function":{"name":"lookup","arguments":"{\"year\":"}}]}}]}"""
        val end = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"2026}"}}]},"finish_reason":"tool_calls"}]}"""
        val data = readChatStream("${start}\n\n${end}\n\n".reader().buffered()) { }
        val call = data.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            .getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("tool-id", call.getString("id"))
        assertEquals("{\"year\":2026}", call.getJSONObject("function").getString("arguments"))
        assertTrue(runCatching { readChatStream(start.reader().buffered()) { } }.isFailure)
    }
}

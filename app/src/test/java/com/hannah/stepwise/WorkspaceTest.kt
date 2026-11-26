package com.hannah.stepwise

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WorkspaceTest {
    // 检查确认前的数据契约，以及跨午夜和重叠事项在网格中的几何位置。
    @Test fun importsTimedAndAllDayRows() {
        val raw = """{"items":[{"title":"读论文","date":"2026-11-24","start":null,"end":null,"type":"event"},
            {"title":"课程","date":"2026-11-24","start":"2026-11-24T23:50","end":"2026-11-25T00:25","type":"course"}]}"""
        val rows = CalendarWire.parse(raw)
        assertEquals(rows, CalendarWire.parse(CalendarWire.encode(rows)))
        assertNull(rows.first().start)
        assertEquals(35L, rows.last().end!! - rows.last().start!!)
        assertTrue(runCatching { CalendarWire.parse(raw.replace("2026-11-25T00:25", "2026-11-24T23:45")) }.isFailure)
    }

    @Test fun overlapsAndSpilloverAreLaidOutFromMinutes() {
        val first = CalendarWire.edit("跨午夜", "2026-11-24", "2026-11-24T23:50", "2026-11-25T00:25", "", "event")
        val second = CalendarWire.edit("作业", "2026-11-25", "2026-11-25T00:15", "2026-11-25T00:40", "", "assignment")
        val before = placeEvents(listOf(first, second), first.day).single()
        assertEquals(1430, before.from)
        assertEquals(1440, before.to)
        val after = placeEvents(listOf(first, second), second.day)
        assertEquals(listOf(0, 15), after.map { it.from })
        assertEquals(listOf(0, 1), after.map { it.column })
        assertTrue(after.all { it.columns == 2 })
    }

    @Test fun weekStartsOnSundayAndMayCrossMonth() {
        val week = weekDays(LocalDate.parse("2026-12-01"))
        assertEquals("2026-11-29", week.first().toString())
        assertEquals("2026-12-05", week.last().toString())
    }

    @Test fun schedulesOnlyMostRecentMissedSlot() {
        val first = LocalDateTime.parse("2026-11-24T19:00")
        val now = LocalDateTime.parse("2026-11-30T18:00")
        assertEquals(first.plusDays(5), JobClock.latest(first, "daily", now))
        assertEquals(first, JobClock.latest(first, "weekly", now))
        assertEquals(first.plusWeeks(1), JobClock.next(first, "weekly"))
        assertNull(JobClock.next(first, "once"))
    }
}

class AssistantWireTest {
    // 伪造接口返回，验证 MCP 初始化、会话头、工具发现与调用，无需任何外部凭据。
    @Test fun mcpSessionHandlesProtocolAndAuthorizationSeparately() {
        val server = McpEndpoint("server", 1, "资料库", "https://example.test/mcp")
        val requests = mutableListOf<String>()
        val transport: Exchange = { url, token, body, headers ->
            assertEquals("https://example.test/mcp", url.toString())
            assertEquals("mock-only", token)
            val method = body.getString("method")
            requests.add(method)
            if (method != "initialize") {
                assertEquals("sid", headers["Mcp-Session-Id"])
                assertEquals("2025-11-25", headers["MCP-Protocol-Version"])
            }
            val result = when (method) {
                "initialize" -> JSONObject("""{"protocolVersion":"2025-11-25"}""")
                "tools/list" -> JSONObject("""{"tools":[{"name":"lookup","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},{"name":"write","inputSchema":{"type":"object"}}]}""")
                "tools/call" -> JSONObject("""{"content":[{"type":"text","text":"ok"}]}""")
                else -> null
            }
            if (result == null) Reply(202, null, null) else Reply(200, JSONObject().put("jsonrpc","2.0")
                .put("id", body.getString("id")).put("result", result), "sid")
        }
        val session = McpSession(server, "mock-only", transport)
        val (lookup, write) = session.tools()
        assertFalse(canOffer(lookup, setOf(lookup.key), emptySet()))
        assertTrue(canOffer(lookup, setOf(lookup.key), setOf(lookup.key)))
        assertFalse(canOffer(write, setOf(write.key), setOf(write.key)))
        assertTrue(canOffer(write, setOf(write.key), null))
        assertFalse(canOffer(lookup.copy(server = server.copy(url = "https://new.test/mcp")),
            setOf(lookup.key), setOf(lookup.key)))
        assertEquals("ok", session.call(lookup, JSONObject()).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), requests)
    }

    @Test fun readsTheMatchingSseResponse() {
        val stream = "data: {\"id\":\"other\"}\n\nevent: message\ndata: {\"id\":\"9\",\ndata: \"result\":{}}\n\n"
        assertEquals("9", readReply(stream.reader().buffered(), "text/event-stream", "9")!!.getString("id"))
    }

    @Test fun deepSeekTemperatureFollowsThinkingMode() {
        val base = ApiSettings()
        val agent = AssistantProfile(model = "custom-model", temperature = 0.3, effort = "high")
        val thinking = AssistantWire.options(base, agent)
        assertFalse(thinking.has("temperature"))
        assertEquals("high", thinking.getString("reasoning_effort"))
        val disabled = AssistantWire.options(base, agent.copy(effort = "none"))
        assertEquals(0.3, disabled.getDouble("temperature"), 0.00001)
        assertEquals("disabled", disabled.getJSONObject("thinking").getString("type"))
    }
}

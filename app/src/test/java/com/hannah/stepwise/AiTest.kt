package com.hannah.stepwise

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class AiTest {
    private val config = ApiSettings(key = "test-only")
    private val course = "第 1 课 FASTQ 格式\n第 2 课质量过滤"
    private val draft = """{"steps":[{"title":"FASTQ","action":"读例子","minutes":20,"source":"FASTQ 格式"}]}"""

    // 构造和实际 API 一样的响应外层，并替换 HTTPS 连接。
    private fun response(content: String) =
        """{"choices":[{"finish_reason":"stop","message":{"content":${JSONObject.quote(content)}}}]}"""

    @Test fun requestAndResponseThroughMockConnection() = runBlocking {
        val mock = Stub(Ai.endpoint(config.base), 200, response(draft))
        val result = Ai.generate(config, course, "每次 20 分钟") { url ->
            assertEquals("https://api.deepseek.com/chat/completions", url.toString())
            mock
        }
        assertEquals(listOf(Draft("FASTQ", "读例子", 20, "FASTQ 格式")), result)
        assertEquals("Bearer test-only", mock.getRequestProperty("Authorization"))
        assertTrue(JSONObject(mock.payload.toString("UTF-8")).getJSONArray("messages")
            .getJSONObject(1).getString("content").contains("FASTQ 格式"))
        assertFalse(mock.instanceFollowRedirects)
        assertTrue(mock.closed)
    }

    @Test fun invalidQuoteAndRateLimitSurfaceErrors() = runBlocking {
        val bad = Stub(Ai.endpoint(config.base), 200, response(draft.replace("FASTQ 格式", "编造原文")))
        val limited = Stub(Ai.endpoint(config.base), 429, "")
        assertTrue(runCatching { Ai.generate(config, course, "") { bad } }.isFailure)
        assertTrue(runCatching { Ai.generate(config, course, "") { limited } }.exceptionOrNull()!!.message!!.contains("额度"))
    }

    private class Stub(url: URL, private val status: Int, private val reply: String) : HttpsURLConnection(url) {
        val payload = ByteArrayOutputStream()
        var closed = false
        override fun getResponseCode() = status
        override fun getOutputStream() = payload
        override fun getInputStream() = reply.byteInputStream()
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = ""
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }
}

package top.yexkr.findscanner

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import top.yexkr.findscanner.data.ApiGateway
import top.yexkr.findscanner.data.ConnectionConfig
import top.yexkr.findscanner.data.retryable
import top.yexkr.findscanner.data.userMessage
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorMessageTest {
    private fun error(body: String, code: Int = 422) = HttpException(Response.error<Any>(code, body.toResponseBody()))

    @Test fun protocolErrorIncludesHttpCodeAndServerCodeAndMessage() {
        val error = error("""{"error":{"code":"VALIDATION_ERROR","message":"设备 ID 不存在"}}""")
        assertEquals("服务器返回 HTTP 422：VALIDATION_ERROR · 设备 ID 不存在", error.userMessage())
        assertEquals(error.userMessage(), error.userMessage())
        assertFalse(error.retryable())
    }

    @Test fun supportsCommonFlatErrorBodies() {
        assertEquals("服务器返回 HTTP 422：区域不存在", error("""{"message":"区域不存在"}""").userMessage())
        assertEquals("服务器返回 HTTP 422：Not Found", error("""{"detail":"Not Found"}""").userMessage())
        assertEquals("服务器返回 HTTP 422：Unavailable", error("""{"error":"Unavailable"}""").userMessage())
        assertEquals("服务器返回 HTTP 422：REGION_NOT_FOUND", error("""{"error":{"code":"REGION_NOT_FOUND"}}""").userMessage())
    }

    @Test fun supportsPlainTextAndNormalizesWhitespace() {
        assertEquals("服务器返回 HTTP 422：服务器繁忙 请稍后重试", error("服务器繁忙\n\t请稍后重试").userMessage())
        assertEquals("服务器返回 HTTP 422：Unavailable", error("Unavailable").userMessage())
        assertEquals("服务器返回 HTTP 422：Not ready", error("\"Not ready\"").userMessage())
    }

    @Test fun htmlMalformedJsonAndEmptyErrorsFallBackToHttpCode() {
        for (body in listOf("", "  ", "<html><body>stack trace</body></html>", "{invalid", "[]", "null")) {
            assertEquals("服务器返回 HTTP 503", error(body, 503).userMessage())
        }
    }

    @Test fun oversizedResponsesDoNotBreakErrorReporting() {
        val message = error("x".repeat(50_000)).userMessage()
        assertTrue(message.startsWith("服务器返回 HTTP 422："))
        assertTrue(message.length < 250)
        assertEquals("服务器返回 HTTP 422", error("{\"message\":\"" + "x".repeat(50_000) + "\"}").userMessage())
    }

    @Test fun networkFailuresHaveUsefulLocalizedMessages() {
        assertTrue(UnknownHostException().userMessage().contains("域名"))
        assertTrue(SocketTimeoutException().userMessage().contains("超时"))
        assertTrue(ConnectException().userMessage().contains("端口"))
        assertTrue(SSLHandshakeException("invalid certificate").userMessage().contains("证书"))
    }

    @Test fun connectionFailurePreservesBackendErrorThroughRetrofit() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"REGION_NOT_FOUND","message":"该区域尚未创建"}}"""))
            val failure = runCatching { ApiGateway("1").connect(ConnectionConfig(server.url("/").toString(), "unknown", "device")) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("服务器返回 HTTP 404：REGION_NOT_FOUND · 该区域尚未创建", failure!!.userMessage())
            assertFalse(failure.retryable())
        } finally { server.shutdown() }
    }
}

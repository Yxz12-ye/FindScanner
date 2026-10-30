package top.yexkr.findscanner

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.yexkr.findscanner.data.*
import java.util.UUID
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal fun frame(id: String = UUID.randomUUID().toString()) = SignalFrame(
    id, UUID.randomUUID().toString(), "实验室", "device-01", 1_000, 11_000,
    listOf(RadioSignal("AA:BB:CC:DD:EE:FF", -55, 9_000, 2_000, true)), emptyList(), null,
    SensorStatuses(SensorStatus("ok"), SensorStatus("empty"), SensorStatus("unavailable", "no_fix"))
)

class ProtocolTest {
    @Test fun normalizesHostsAndDebug() {
        assertEquals("http://192.168.1.2:8000/", normalizeServer(" 192.168.1.2:8000 "))
        assertEquals("https://example.com/", normalizeServer("https://example.com"))
        assertEquals("debug", normalizeServer(" DeBuG "))
        assertTrue(ConnectionConfig(" DEBUG ").isDebug)
        assertFalse(ConnectionConfig("http://debug/").isDebug)
    }
    @Test fun rejectsInvalidAddressesAndMissingIdentity() {
        listOf("", "ftp://localhost", "http://a/path", "http://user:pass@a", "http://a?q=x", "http://a#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeServer(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("debug", "", "id").validated() }
        assertThrows(IllegalArgumentException::class.java) { ConnectionConfig("debug", "region", " ").validated() }
    }
    @Test fun labelOnlyTrimsOutsideWhitespaceAndInferenceHasNoLabel() {
        val frame = frame()
        assertEquals("餐厅 (1, 2)", frame.sample("  餐厅 (1, 2)  ")["locationLabel"]!!.jsonPrimitive.content)
        assertFalse(wireJson.encodeToJsonElement(frame).jsonObject.containsKey("locationLabel"))
        assertEquals(frame, wireJson.decodeFromString<SignalFrame>(frame.sample("x").toString()))
        assertThrows(IllegalArgumentException::class.java) { frame.sample(" \n ") }
    }
    @Test fun windowUsesMonotonicTimeAndNewWindowDiscardsIncompleteTime() {
        val window = SamplingWindow(1_000, 50_000)
        assertFalse(window.complete(10_999)); assertTrue(window.complete(11_000))
        assertEquals(.5f, window.progress(6_000), .0001f)
        assertEquals(60_000, window.endedAt(11_000))
        assertFalse(SamplingWindow(30_000, 80_000).complete(35_000))
    }
    @Test fun validatesAndSortsInferenceProbabilities() {
        val response = InferenceResponse("id", 1, "ok", listOf(Candidate("b", .2), Candidate("a", .8)))
        assertEquals("a", response.validate("id").candidates.first().locationLabel)
        assertThrows(IllegalArgumentException::class.java) { response.validate("other") }
        assertThrows(IllegalArgumentException::class.java) { response.copy(candidates = listOf(Candidate("a", 1.1))).validate("id") }
        assertThrows(IllegalArgumentException::class.java) { response.copy(status = "no_model").validate("id") }
        assertThrows(IllegalArgumentException::class.java) { response.copy(candidates = emptyList()).validate("id") }
        assertThrows(IllegalArgumentException::class.java) { response.copy(candidates = listOf(Candidate("a", .5), Candidate("a", .5))).validate("id") }
        InferenceResponse("id", 1, "no_match", emptyList()).validate("id")
    }
    @Test fun retryDelayIsBounded() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L), (1..3).map(::retryDelayMs))
        assertEquals(300_000L, retryDelayMs(100))
    }
    @Test fun onlyTransientHttpStatusesAreAutomaticallyRetried() {
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            assertTrue(HttpException(Response.error<Any>(code, "error".toResponseBody())).retryable())
        }
        listOf(400, 401, 403, 404, 409, 422).forEach { code ->
            assertFalse(HttpException(Response.error<Any>(code, "error".toResponseBody())).retryable())
        }
    }
    @Test fun connectionRejectsUnsupportedProtocol() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"protocolVersion":2,"inferenceReady":true}"""))
            assertTrue(runCatching { ApiGateway("1").connect(ConnectionConfig(server.url("/").toString(), "r", "d")) }.exceptionOrNull() is IllegalArgumentException)
        } finally { server.shutdown() }
    }
    @Test fun mockServerVerifiesAllThreeEndpointsAndImmutableUpload() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val api = ApiGateway("1.0")
            val config = ConnectionConfig(server.url("/").toString(), "实验室", "id").validated()
            server.enqueue(MockResponse().setBody("""{"protocolVersion":1,"inferenceReady":true}"""))
            assertTrue(api.connect(config).inferenceReady)
            val connect = server.takeRequest()
            assertEquals("POST", connect.method)
            assertEquals("/api/v1/connect", connect.path)
            val connection = wireJson.parseToJsonElement(connect.body.readUtf8()).jsonObject
            assertEquals("id", connection["deviceId"]!!.jsonPrimitive.content)
            assertEquals(1, connection["protocolVersion"]!!.jsonPrimitive.int)
            val frame = frame()
            val record = SampleRecord(frame.id, config.server, frame.sample("(1,2)").toString(), 11_000, UploadStatus.PENDING)
            listOf("accepted", "duplicate").forEach { status ->
                server.enqueue(MockResponse().setBody("""{"id":"${frame.id}","status":"$status"}"""))
                api.upload(record)
                val sample = server.takeRequest()
                assertEquals("/api/v1/samples", sample.path)
                assertEquals(record.payload, sample.body.readUtf8())
            }
            server.enqueue(MockResponse().setBody("""{"id":"${frame.id}","inferredAt":12000,"status":"ok","candidates":[{"locationLabel":"餐厅","probability":1.0}]}"""))
            assertEquals("餐厅", api.infer(config, frame).candidates.single().locationLabel)
            val inference = server.takeRequest()
            assertEquals("/api/v1/inferences", inference.path)
            assertFalse(wireJson.parseToJsonElement(inference.body.readUtf8()).jsonObject.containsKey("locationLabel"))
        } finally { server.shutdown() }
    }
    @Test fun debugGatewayDoesNotCreateAnyNetworkRequest() = runTest {
        val gateway = ApiGateway("1")
        assertFalse(gateway.connect(ConnectionConfig("debug")).inferenceReady)
        val frame = frame()
        val result = runCatching { gateway.infer(ConnectionConfig("debug"), frame) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        val record = SampleRecord(frame.id, "debug", frame.sample("餐厅").toString(), 11_000, UploadStatus.LOCAL_ONLY)
        assertTrue(runCatching { gateway.upload(record) }.exceptionOrNull() is IllegalStateException)
    }
    @Test fun invalidAcknowledgementIsRejected() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"id":"wrong","status":"accepted"}"""))
            val frame = frame()
            val record = SampleRecord(frame.id, server.url("/").toString(), frame.sample("x").toString(), 11_000, UploadStatus.PENDING)
            assertTrue(runCatching { ApiGateway("1").upload(record) }.exceptionOrNull() is IllegalArgumentException)
        } finally { server.shutdown() }
    }
}

package top.yexkr.findscanner.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

interface ScannerApi {
    @POST("api/v1/connect") suspend fun connect(@Body request: ConnectRequest): ConnectResponse
    @POST("api/v1/samples") suspend fun sample(@Body request: JsonObject): SampleAck
    @POST("api/v1/inferences") suspend fun infer(@Body request: SignalFrame): InferenceResponse
}

interface ScannerGateway {
    suspend fun connect(config: ConnectionConfig): ConnectResponse
    suspend fun upload(record: SampleRecord)
    suspend fun infer(config: ConnectionConfig, frame: SignalFrame): InferenceResponse
}

class ApiGateway(private val version: String) : ScannerGateway {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private fun api(server: String): ScannerApi {
        check(!ConnectionConfig(server).isDebug) { "debug 模式禁止网络请求" }
        return Retrofit.Builder().baseUrl(normalizeServer(server)).client(client)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType()))
            .build().create(ScannerApi::class.java)
    }
    override suspend fun connect(config: ConnectionConfig): ConnectResponse {
        if (config.isDebug) return ConnectResponse(1, false)
        val response = api(config.server).connect(ConnectRequest(config.region, config.deviceId, version))
        require(response.protocolVersion == 1) { "服务器协议不兼容，需要版本 1" }
        return response
    }
    override suspend fun upload(record: SampleRecord) {
        check(!ConnectionConfig(record.server).isDebug && record.status != UploadStatus.LOCAL_ONLY) { "调试记录禁止上传" }
        val ack = api(record.server).sample(wireJson.parseToJsonElement(record.payload) as JsonObject)
        require(ack.id == record.id && ack.status in listOf("accepted", "duplicate")) { "服务器确认 ID 或状态不匹配" }
    }
    override suspend fun infer(config: ConnectionConfig, frame: SignalFrame): InferenceResponse {
        check(!config.isDebug) { "debug 模式下位置推理不可用" }
        return api(config.server).infer(frame).validate(frame.id)
    }
}

fun Throwable.retryable(): Boolean = when (this) {
    is HttpException -> code() == 408 || code() == 429 || code() >= 500
    is IOException -> true
    else -> false
}
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> "服务器返回 HTTP ${code()}${serverErrorDetail()?.let { "：$it" }.orEmpty()}"
    is UnknownHostException -> "无法解析服务器地址，请检查 IP 或域名"
    is SocketTimeoutException -> "连接或请求超时，请检查服务器与网络"
    is ConnectException -> "无法连接服务器，请检查地址、端口及服务是否启动"
    is SSLException -> "HTTPS 连接失败，请检查服务器证书及 TLS 配置"
    is IOException -> "网络不可用或请求中断：${message?.noticeText() ?: "连接失败"}"
    is SerializationException -> "服务器响应格式不正确，请检查 API 协议"
    else -> message ?: "操作失败"
}

private fun String.noticeText() = replace(Regex("[\\p{Cc}\\s]+"), " ").trim().take(200)

private fun HttpException.serverErrorDetail(): String? = runCatching {
    // Read a bounded preview without consuming the response, so diagnostics can be reused.
    val raw = response()?.errorBody()?.source()?.peek()?.use { source ->
        source.request(4096)
        source.readUtf8(minOf(source.buffer.size, 4096L))
    }?.trim().orEmpty()
    if (raw.isBlank() || raw.startsWith("<")) return@runCatching null
    val parsed = runCatching { wireJson.parseToJsonElement(raw) }.getOrNull()
    fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.noticeText()?.takeIf { it.isNotBlank() }
    val root = parsed as? JsonObject
    val error = root?.get("error") as? JsonObject
    val message = error?.get("message").text() ?: root?.get("message").text()
        ?: root?.get("detail").text() ?: root?.get("error").text() ?: parsed.text()
    val code = error?.get("code").text() ?: root?.get("code").text()
    when {
        message != null -> listOfNotNull(code, message).joinToString(" · ").noticeText()
        code != null -> code
        parsed != JsonNull && !raw.startsWith("{") && !raw.startsWith("[") -> raw.noticeText()
        else -> null
    }
}.getOrNull()

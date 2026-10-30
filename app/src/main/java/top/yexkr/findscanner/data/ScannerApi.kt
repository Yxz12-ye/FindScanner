package top.yexkr.findscanner.data

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
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
    is HttpException -> "服务器返回 HTTP ${code()}"
    is IOException -> "网络不可用或请求超时：${message ?: "连接失败"}"
    else -> message ?: "操作失败"
}

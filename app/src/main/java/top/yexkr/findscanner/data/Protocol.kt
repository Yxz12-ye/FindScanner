package top.yexkr.findscanner.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

@Serializable
data class ConnectionConfig(val server: String = "", val region: String = "", val deviceId: String = "") {
    val isDebug: Boolean get() = server.trim().equals("debug", ignoreCase = true)
    fun validated(): ConnectionConfig {
        require(region.isNotBlank()) { "请填写区域" }
        require(deviceId.isNotBlank()) { "请填写设备 ID" }
        return copy(server = normalizeServer(server), region = region.trim(), deviceId = deviceId.trim())
    }
}

fun normalizeServer(value: String): String {
    val trimmed = value.trim()
    if (trimmed.equals("debug", true)) return "debug"
    require(trimmed.isNotEmpty()) { "请填写服务器地址" }
    val url = (if ("://" in trimmed) trimmed else "http://$trimmed").toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请输入有效的 HTTP / HTTPS 服务器地址")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
        "服务器地址只填写主机与端口，不包含路径、认证信息或查询参数"
    }
    return url.toString()
}

@Serializable data class RadioSignal(
    val mac: String, val rssi: Int, val observedAt: Long, val ageMs: Long,
    val fresh: Boolean, val name: String? = null, val frequencyMhz: Int? = null
)
@Serializable data class GpsFix(
    val latitude: Double, val longitude: Double, val accuracyMeters: Float,
    val observedAt: Long, val altitudeMeters: Double? = null
)
@Serializable data class SensorStatus(val status: String, val reason: String? = null)
@Serializable data class SensorStatuses(val wifi: SensorStatus, val ble: SensorStatus, val gps: SensorStatus)
@Serializable data class SignalFrame(
    val id: String, val sessionId: String, val region: String, val deviceId: String,
    val windowStartedAt: Long, val windowEndedAt: Long,
    val wifi: List<RadioSignal>, val ble: List<RadioSignal>, val gps: GpsFix?, val sensors: SensorStatuses
) {
    val signalCount: Int get() = wifi.size + ble.size
    val averageRssi: String get() = (wifi + ble).takeIf { it.isNotEmpty() }?.map { it.rssi }?.average()?.toInt()?.toString() ?: "—"
    fun sample(label: String): JsonObject {
        require(label.isNotBlank()) { "请填写采集位置" }
        return JsonObject(wireJson.encodeToJsonElement(this).jsonObject + ("locationLabel" to JsonPrimitive(label.trim())))
    }
}
@Serializable data class ConnectRequest(val region: String, val deviceId: String, val clientVersion: String, val protocolVersion: Int = 1)
@Serializable data class ConnectResponse(val protocolVersion: Int, val inferenceReady: Boolean)
@Serializable data class SampleAck(val id: String, val status: String)
@Serializable data class Candidate(val locationLabel: String, val probability: Double)
@Serializable data class InferenceResponse(val id: String, val inferredAt: Long, val status: String, val candidates: List<Candidate>) {
    fun validate(expectedId: String): InferenceResponse {
        require(id == expectedId && inferredAt > 0) { "推理响应 ID 或时间无效" }
        require(status in listOf("ok", "no_model", "no_match")) { "未知推理状态" }
        require(candidates.all { it.locationLabel.isNotBlank() && it.probability.isFinite() && it.probability in 0.0..1.0 }) { "候选概率无效" }
        require(candidates.map { it.locationLabel }.distinct().size == candidates.size) { "候选位置重复" }
        if (status == "ok") require(candidates.isNotEmpty() && kotlin.math.abs(candidates.sumOf { it.probability } - 1.0) <= .01) { "候选概率总和必须为 1" }
        else require(candidates.isEmpty()) { "无模型或无匹配时不能包含概率" }
        return copy(candidates = candidates.sortedByDescending { it.probability })
    }
}
@Serializable data class InferenceHistory(val frame: SignalFrame, val result: InferenceResponse?, val message: String)

enum class UploadStatus(val label: String) {
    PENDING("待上传"), UPLOADED("已上传"), FAILED("上传失败"), REJECTED("上传被拒绝"), LOCAL_ONLY("仅本地 · debug")
}
data class SampleRecord(
    val id: String, val server: String, val payload: String, val createdAt: Long,
    val status: UploadStatus, val attempts: Int = 0, val nextRetryAt: Long = 0, val error: String? = null
) {
    val frame: SignalFrame by lazy { wireJson.decodeFromString(payload) }
    val locationLabel: String by lazy { wireJson.parseToJsonElement(payload).jsonObject.getValue("locationLabel").jsonPrimitive.content }
    val uploadable: Boolean get() = !ConnectionConfig(server).isDebug && status in listOf(UploadStatus.PENDING, UploadStatus.FAILED)
}

fun retryDelayMs(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 9)).coerceAtMost(300_000L)

/** Only monotonic time determines a completed window; pausing discards the instance. */
class SamplingWindow(val startedElapsed: Long, val startedAt: Long, val durationMs: Long = 10_000) {
    fun progress(nowElapsed: Long): Float = ((nowElapsed - startedElapsed).toFloat() / durationMs).coerceIn(0f, 1f)
    fun complete(nowElapsed: Long): Boolean = nowElapsed - startedElapsed >= durationMs
    fun endedAt(nowElapsed: Long): Long = startedAt + (nowElapsed - startedElapsed).coerceAtLeast(0)
}

package top.yexkr.findscanner

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import top.yexkr.findscanner.data.*
import top.yexkr.findscanner.scanner.AndroidSignalCollector
import top.yexkr.findscanner.scanner.SignalCollector
import java.util.UUID

enum class Page { Permissions, Connection, Home, Collection, Detail, Inference, SampleHistory, InferenceHistory }
data class ScannerState(
    val page: Page = Page.Permissions, val inferenceTab: Boolean = false,
    val draft: ConnectionConfig = ConnectionConfig(), val config: ConnectionConfig? = null,
    val location: String = "", val sessionId: String = "", val sessionLabel: String = "",
    val running: Boolean = false, val inferring: Boolean = false,
    val progress: Float = 0f, val connecting: Boolean = false, val ready: Boolean = false,
    val inferenceReady: Boolean = false, val selectedId: String? = null, val detailFromHistory: Boolean = false,
    val historyReturnPage: Page = Page.Connection, val inferenceFromHistory: Boolean = false,
    val inference: InferenceHistory? = null, val inferenceMessage: String = "等待首轮采样", val expired: Boolean = false,
    val error: String? = null, val deviceStatus: String = "", val granted: Set<String> = emptySet()
)

class ScannerViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: ScannerRepository = (application as ScannerApplication).repository,
    private val configStore: ConnectionSettings = (application as ScannerApplication).configStore,
    private val collector: SignalCollector = AndroidSignalCollector(application),
    private val windowDurationMs: Long = 10_000
) : AndroidViewModel(application) {
    private val app = application
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(ScannerState())
    val state = _state.asStateFlow()
    val samples = repository.samples
    val inferences = repository.inferences
    private var foreground = false
    private var samplingJob: Job? = null
    private var uploadJob: Job? = null
    private var inferenceJob: Job? = null
    private var connectJob: Job? = null
    private var generation = 0
    private var inferenceElapsed = 0L
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val notices = Channel<String>(Channel.BUFFERED)
    val notifications = notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                val config = configStore.config.first()
                repository.reload()
                _state.update { it.copy(draft = config, ready = true) }
            } catch (e: Exception) { fail(e); _state.update { it.copy(ready = true) } }
        }
        viewModelScope.launch {
            for (write in writes) {
                try { write() }
                catch (e: Exception) { fail(e); pause() }
            }
        }
    }
    fun clearError() { _state.update { it.copy(error = null) } }
    private fun notifyUser(message: String) { notices.trySend(message) }
    private fun showError(message: String) { _state.update { it.copy(error = message) }; notifyUser(message) }
    private fun fail(error: Exception, context: String? = null) {
        if (error !is CancellationException) showError(listOfNotNull(context, error.userMessage()).joinToString("："))
    }
    fun permissions(granted: Set<String>) { _state.update { it.copy(granted = granted, deviceStatus = collector.deviceStatus()) } }
    fun editServer(value: String) { _state.update { it.copy(draft = it.draft.copy(server = value), error = null) } }
    fun editRegion(value: String) { _state.update { it.copy(draft = it.draft.copy(region = value), error = null) } }
    fun editDevice(value: String) { _state.update { it.copy(draft = it.draft.copy(deviceId = value), error = null) } }
    fun location(value: String) { _state.update { it.copy(location = value) } }
    fun tab(inference: Boolean) { _state.update { it.copy(inferenceTab = inference) } }
    fun navigate(page: Page) {
        if (page != Page.Connection) connectJob?.cancel()
        if (page !in listOf(Page.Collection, Page.Detail)) pause()
        if (page in listOf(Page.Connection, Page.Permissions)) uploadJob?.cancel()
        _state.update { it.navigateTo(page) }
        if (state.value.page == Page.Home) startUploader()
    }
    fun back() {
        navigate(state.value.backPage())
    }
    fun connect() {
        if (state.value.connecting || !state.value.ready || !foreground) return
        pause(); uploadJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(connecting = true, error = null) }
            try {
                val config = state.value.draft.validated()
                val result = repository.connect(config)
                configStore.save(config)
                _state.update { it.copy(config = config, draft = config, inferenceReady = result.inferenceReady,
                    page = Page.Home, inference = null, expired = false, inferenceMessage = "等待首轮采样") }
                startUploader()
                notifyUser(if (config.isDebug) "已进入本地调试模式，采集数据不会上传" else "服务器连接成功")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { fail(e, "连接失败") }
            finally { _state.update { it.copy(connecting = false) } }
        }
    }
    fun prepareCollection() {
        if (state.value.config == null) { navigate(Page.Connection); return }
        if (state.value.location.isBlank()) { showError("请填写采集位置"); return }
        pause()
        _state.update { it.copy(page = Page.Collection, sessionId = UUID.randomUUID().toString(), sessionLabel = it.location.trim()) }
    }
    fun detail(id: String, history: Boolean = false) { _state.update { it.copy(page = Page.Detail, selectedId = id, detailFromHistory = history) } }
    fun showInference(history: InferenceHistory) {
        pause(); _state.update { it.copy(page = Page.Inference, inference = history, inferenceMessage = history.message, expired = true,
            inferenceFromHistory = true) }
    }
    fun toggleCollection() { if (state.value.running) pause() else start(false) }
    fun toggleInference() { if (state.value.inferring) pause() else start(true) }
    fun startInference() {
        if (state.value.config == null) { navigate(Page.Connection); return }
        if (state.value.config?.isDebug == true) { showError("debug 模式下位置推理不可用"); return }
        _state.update { it.copy(page = Page.Inference, sessionId = UUID.randomUUID().toString(), inference = null, expired = false,
            inferenceFromHistory = false) }
        start(true)
    }
    private fun start(inference: Boolean) {
        val config = state.value.config ?: return
        if (!foreground || samplingJob?.isActive == true) return
        if (inference && config.isDebug) { showError("debug 模式下位置推理不可用"); return }
        if (!inference && state.value.sessionLabel.isBlank()) return
        val sessionId = state.value.sessionId.ifBlank { UUID.randomUUID().toString() }
        val label = state.value.sessionLabel
        val token = ++generation
        collector.start()
        _state.update { it.copy(running = !inference, inferring = inference, progress = 0f,
            sessionId = sessionId, inferenceMessage = if (inference) "等待首轮采样" else it.inferenceMessage) }
        samplingJob = viewModelScope.launch {
            var window = SamplingWindow(SystemClock.elapsedRealtime(), System.currentTimeMillis(), windowDurationMs)
            try {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    if (window.complete(now)) {
                        val boundary = window.startedElapsed + window.durationMs
                        val end = if (now - boundary < window.durationMs) boundary else now
                        val frame = collector.snapshot(config, sessionId, window, end)
                        if (inference) submitInference(config, frame, token) else writes.send { repository.save(config, frame, label) }
                        // Anchor ordinary windows to the monotonic schedule; never manufacture missed rounds.
                        val next = window.startedElapsed + window.durationMs
                        window = if (now - next < window.durationMs) SamplingWindow(next, window.startedAt + window.durationMs, windowDurationMs)
                            else SamplingWindow(now, window.endedAt(now), windowDurationMs)
                    }
                    _state.update { it.copy(progress = window.progress(now)) }
                    delay(100)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { fail(e); pause() }
            finally { if (token == generation) collector.stop() }
        }
    }
    private fun submitInference(config: ConnectionConfig, frame: SignalFrame, token: Int) {
        if (inferenceJob?.isActive == true) return // No queue or replay for inference.
        inferenceJob = viewModelScope.launch {
            var failure: String? = null
            val history = try {
                if (frame.signalCount == 0 && frame.gps == null) InferenceHistory(frame, null, "没有可用信号")
                else {
                    _state.update { it.copy(inferenceMessage = "正在推理") }
                    val result = repository.infer(config, frame)
                    InferenceHistory(frame, result, when (result.status) { "ok" -> "推理完成"; "no_model" -> "本区域暂无模型"; else -> "没有匹配位置" })
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { val message = e.userMessage(); failure = message; InferenceHistory(frame, null, message) }
            if (token != generation || !foreground || !state.value.inferring) return@launch
            failure?.let { if (it != state.value.inference?.message) notifyUser("推理失败：$it") }
            inferenceElapsed = SystemClock.elapsedRealtime()
            _state.update { it.copy(inference = history, inferenceMessage = history.message, expired = false) }
            // A completed, accepted result must survive a subsequent pause/navigation cancellation.
            writes.send { repository.saveInference(history) }
        }
    }
    fun pause() {
        generation++
        samplingJob?.cancel(); samplingJob = null
        inferenceJob?.cancel(); inferenceJob = null
        collector.stop()
        _state.update { it.copy(running = false, inferring = false, progress = 0f, expired = it.inference != null || it.expired,
            inferenceMessage = if (it.inferring && it.inference == null) "推理已中止" else it.inferenceMessage) }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { pause(); uploadJob?.cancel(); connectJob?.cancel() }
        else { permissions(state.value.granted); startUploader() }
    }
    private fun online(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(network)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
    }.getOrDefault(false)
    private fun uploadsAllowed() = foreground && state.value.config?.isDebug == false &&
        state.value.page !in listOf(Page.Connection, Page.Permissions) && online()
    private fun startUploader() {
        if (!foreground || uploadJob?.isActive == true) return
        uploadJob = viewModelScope.launch {
            while (isActive) {
                try { repository.uploadDue(System.currentTimeMillis(), ::uploadsAllowed) }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { fail(e) }
                if (state.value.inference != null && SystemClock.elapsedRealtime() - inferenceElapsed > 30_000)
                    _state.update { it.copy(expired = true) }
                delay(1_000)
            }
        }
    }
    fun retryUpload(id: String) {
        if (state.value.config?.isDebug == true) { showError("debug 模式不上传任何数据"); return }
        viewModelScope.launch {
            try {
                repository.retry(id)
                notifyUser(if (samples.value.any { it.id == id && it.status == UploadStatus.PENDING }) "已加入上传重试队列" else "当前记录无需重试")
            } catch (e: Exception) { fail(e, "重试失败") }
        }
    }
    fun clearHistory() {
        pause()
        _state.update { it.copy(inference = null, selectedId = null, expired = false, inferenceMessage = "等待首轮采样") }
        writes.trySend { repository.clear(); notifyUser("本地历史已清理") }
    }
    override fun onCleared() { collector.stop(); writes.close(); notices.close() }
}

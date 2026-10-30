package top.yexkr.findscanner.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScannerRepository(private val store: RecordStore, private val gateway: ScannerGateway) {
    private val _samples = MutableStateFlow<List<SampleRecord>>(emptyList())
    val samples = _samples.asStateFlow()
    private val _inferences = MutableStateFlow<List<InferenceHistory>>(emptyList())
    val inferences = _inferences.asStateFlow()
    private val storageMutex = Mutex()
    private val uploadMutex = Mutex()
    suspend fun reload() = storageMutex.withLock { refresh() }
    private suspend fun refresh() {
        _samples.value = store.samples()
        _inferences.value = store.inferences()
    }
    suspend fun connect(config: ConnectionConfig): ConnectResponse =
        if (config.isDebug) ConnectResponse(1, false) else gateway.connect(config)

    suspend fun save(config: ConnectionConfig, frame: SignalFrame, label: String) = storageMutex.withLock {
        val record = SampleRecord(frame.id, config.server, frame.sample(label).toString(), frame.windowEndedAt,
            if (config.isDebug) UploadStatus.LOCAL_ONLY else UploadStatus.PENDING)
        store.insert(record) // The immutable payload and original destination are saved BEFORE any upload.
        refresh()
    }
    suspend fun saveInference(history: InferenceHistory) = storageMutex.withLock {
        store.insertInference(history); refresh()
    }
    suspend fun infer(config: ConnectionConfig, frame: SignalFrame): InferenceResponse {
        check(!config.isDebug) { "debug 模式下位置推理不可用" }
        return gateway.infer(config, frame).validate(frame.id)
    }
    /** currentAllowed is rechecked for every record; debug suppresses old normal queues as well. */
    suspend fun uploadDue(now: Long, currentAllowed: () -> Boolean, onlyId: String? = null) = uploadMutex.withLock {
        if (!currentAllowed()) return@withLock
        val records = samples.value.asReversed().filter {
            it.uploadable && (onlyId == null && it.nextRetryAt <= now || it.id == onlyId)
        }
        for (record in records) {
            if (!currentAllowed()) break
            val updated = try {
                gateway.upload(record)
                record.copy(status = UploadStatus.UPLOADED, attempts = record.attempts + 1, error = null, nextRetryAt = 0)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                record.copy(status = if (error.retryable()) UploadStatus.FAILED else UploadStatus.REJECTED,
                    attempts = record.attempts + 1, nextRetryAt = now + retryDelayMs(record.attempts + 1), error = error.userMessage())
            }
            storageMutex.withLock { store.update(updated); refresh() }
        }
    }
    suspend fun retry(id: String) = storageMutex.withLock {
        val record = samples.value.find { it.id == id } ?: return@withLock
        if (record.status !in listOf(UploadStatus.FAILED, UploadStatus.REJECTED)) return@withLock
        if (ConnectionConfig(record.server).isDebug) return@withLock
        store.update(record.copy(status = UploadStatus.PENDING, nextRetryAt = 0)); refresh()
    }
    suspend fun clear() = uploadMutex.withLock { storageMutex.withLock { store.clear(); refresh() } }
}

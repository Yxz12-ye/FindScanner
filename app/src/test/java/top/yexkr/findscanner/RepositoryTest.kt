package top.yexkr.findscanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.yexkr.findscanner.data.*
import java.io.IOException

internal class MemoryStore : RecordStore {
    val records = linkedMapOf<String, SampleRecord>()
    val history = mutableListOf<InferenceHistory>()
    override suspend fun samples() = records.values.toList().sortedByDescending { it.createdAt }
    override suspend fun insert(record: SampleRecord) { check(record.id !in records); records[record.id] = record }
    override suspend fun update(record: SampleRecord) { if (record.id in records) records[record.id] = record }
    override suspend fun inferences() = history.toList()
    override suspend fun insertInference(history: InferenceHistory) { this.history.add(history) }
    override suspend fun clear() { records.clear(); history.clear() }
}
internal class FakeGateway : ScannerGateway {
    var connections = 0
    var inferences = 0
    val uploads = mutableListOf<SampleRecord>()
    var uploadError: Exception? = null
    var onUpload: (SampleRecord) -> Unit = {}
    override suspend fun connect(config: ConnectionConfig): ConnectResponse { connections++; return ConnectResponse(1, true) }
    override suspend fun upload(record: SampleRecord) { onUpload(record); uploads += record; uploadError?.let { throw it } }
    override suspend fun infer(config: ConnectionConfig, frame: SignalFrame): InferenceResponse {
        inferences++; return InferenceResponse(frame.id, 1, "no_model", emptyList())
    }
}
class RepositoryTest {
    private val normal = ConnectionConfig("http://first.example/", "region", "device")
    private val debug = normal.copy(server = "debug")
    @Test fun debugBypassesGatewayAndLocalRecordsNeverEnterQueueEvenAfterRestart() = runTest {
        val store = MemoryStore(); val gateway = FakeGateway(); val repository = ScannerRepository(store, gateway)
        assertFalse(repository.connect(debug).inferenceReady); assertEquals(0, gateway.connections)
        repository.save(debug, frame(), " 餐厅 ")
        assertEquals(UploadStatus.LOCAL_ONLY, repository.samples.value.single().status)
        assertEquals("餐厅", repository.samples.value.single().locationLabel)
        val restarted = ScannerRepository(store, gateway); restarted.reload()
        restarted.uploadDue(Long.MAX_VALUE, { true })
        assertTrue(gateway.uploads.isEmpty())
        assertTrue(runCatching { restarted.infer(debug, frame()) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, gateway.inferences)
    }
    @Test fun debugSuppressesPreviouslyQueuedNormalRecords() = runTest {
        val gateway = FakeGateway(); val repository = ScannerRepository(MemoryStore(), gateway)
        repository.save(normal, frame(), "x")
        repository.uploadDue(12_000, { false })
        assertTrue(gateway.uploads.isEmpty()); assertEquals(UploadStatus.PENDING, repository.samples.value.single().status)
    }
    @Test fun savesBeforeUploadingAndKeepsOriginalDestinationAndPayloadAcrossRetry() = runTest {
        val store = MemoryStore(); val gateway = FakeGateway(); val repository = ScannerRepository(store, gateway)
        val frame = frame()
        repository.save(normal, frame, "(1,2)")
        gateway.onUpload = { assertEquals(it.payload, store.records.getValue(it.id).payload) }
        gateway.uploadError = IOException("offline")
        repository.uploadDue(11_000, { true })
        val failed = repository.samples.value.single()
        assertEquals(UploadStatus.FAILED, failed.status); assertEquals(12_000, failed.nextRetryAt)
        repository.uploadDue(11_999, { true }); assertEquals(1, gateway.uploads.size)
        gateway.uploadError = null
        repository.uploadDue(12_000, { true })
        assertEquals(2, gateway.uploads.size)
        assertEquals(gateway.uploads[0].payload, gateway.uploads[1].payload)
        assertEquals(normal.server, gateway.uploads[1].server)
        assertEquals(UploadStatus.UPLOADED, repository.samples.value.single().status)
        repository.uploadDue(Long.MAX_VALUE, { true }); assertEquals(2, gateway.uploads.size)
    }
    @Test fun rechecksNetworkGateBetweenRecords() = runTest {
        val gateway = FakeGateway(); val repository = ScannerRepository(MemoryStore(), gateway)
        repeat(3) { repository.save(normal, frame(), "x") }
        var allowed = true; gateway.onUpload = { allowed = false }
        repository.uploadDue(12_000, { allowed })
        assertEquals(1, gateway.uploads.size)
        assertEquals(2, repository.samples.value.count { it.status == UploadStatus.PENDING })
    }
    @Test fun malformedAcknowledgementIsNotAutomaticallyRetriedButCanBeManuallyRetried() = runTest {
        val gateway = FakeGateway(); val repository = ScannerRepository(MemoryStore(), gateway)
        val frame = frame(); repository.save(normal, frame, "x")
        gateway.uploadError = IllegalArgumentException("wrong ack")
        repository.uploadDue(12_000, { true })
        assertEquals(UploadStatus.REJECTED, repository.samples.value.single().status)
        repository.uploadDue(Long.MAX_VALUE, { true }); assertEquals(1, gateway.uploads.size)
        repository.retry(frame.id); gateway.uploadError = null
        repository.uploadDue(12_000, { true })
        assertEquals(UploadStatus.UPLOADED, repository.samples.value.single().status)
    }
    @Test fun canceledUploadRemainsPendingForIdempotentRetry() = runTest {
        val gateway = FakeGateway(); val repository = ScannerRepository(MemoryStore(), gateway)
        repository.save(normal, frame(), "x"); gateway.uploadError = CancellationException()
        assertTrue(runCatching { repository.uploadDue(12_000, { true }) }.exceptionOrNull() is CancellationException)
        assertEquals(UploadStatus.PENDING, repository.samples.value.single().status)
    }
    @Test fun clearRemovesSamplesAndInferenceHistory() = runTest {
        val repository = ScannerRepository(MemoryStore(), FakeGateway())
        repository.save(normal, frame(), "x"); repository.saveInference(InferenceHistory(frame(), null, "无信号"))
        repository.clear(); assertTrue(repository.samples.value.isEmpty()); assertTrue(repository.inferences.value.isEmpty())
    }
}

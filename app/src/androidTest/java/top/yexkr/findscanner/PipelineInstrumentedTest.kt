package top.yexkr.findscanner

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import top.yexkr.findscanner.data.*
import top.yexkr.findscanner.scanner.SignalCollector
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PipelineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as Application
    private fun database(name: String) = Room.databaseBuilder(context, ScannerDatabase::class.java, name).build()
    private fun testFrame(config: ConnectionConfig, session: String, window: SamplingWindow, now: Long) = SignalFrame(
        UUID.randomUUID().toString(), session, config.region, config.deviceId, window.startedAt, window.endedAt(now),
        listOf(RadioSignal("AA:BB:CC:DD:EE:01", -50, window.startedAt, now - window.startedElapsed, true)), emptyList(), null,
        SensorStatuses(SensorStatus("ok"), SensorStatus("empty"), SensorStatus("unavailable", "no_fix"))
    )
    private class Settings : ConnectionSettings {
        override val config = MutableStateFlow(ConnectionConfig())
        override suspend fun save(config: ConnectionConfig) { this.config.value = config }
    }
    private inner class Collector : SignalCollector {
        var active = false
        var starts = 0
        override fun start() { active = true; starts++ }
        override fun stop() { active = false }
        override fun deviceStatus() = "test hardware"
        override fun snapshot(config: ConnectionConfig, sessionId: String, window: SamplingWindow, nowElapsed: Long) = testFrame(config, sessionId, window, nowElapsed)
    }
    private class Gateway : ScannerGateway {
        var connects = 0; var uploads = 0; var inferences = 0
        var heldResponse: CompletableDeferred<Unit>? = null
        override suspend fun connect(config: ConnectionConfig): ConnectResponse { connects++; return ConnectResponse(1, true) }
        override suspend fun upload(record: SampleRecord) { uploads++ }
        override suspend fun infer(config: ConnectionConfig, frame: SignalFrame): InferenceResponse {
            inferences++
            // Emulates a callback/network implementation that completes even after cancellation.
            heldResponse?.let { withContext(NonCancellable) { it.await() } }
            return InferenceResponse(frame.id, System.currentTimeMillis(), "ok", listOf(Candidate("餐厅", 1.0)))
        }
    }
    private suspend fun connect(model: ScannerViewModel, server: String) {
        withTimeout(5_000) { model.state.first { it.ready } }
        withContext(Dispatchers.Main) {
            model.setForeground(true); model.navigate(Page.Connection)
            model.editServer(server); model.editRegion("测试区域"); model.editDevice("test-device"); model.connect()
        }
        withTimeout(5_000) { model.state.first { it.page == Page.Home } }
    }

    @Test fun roomSurvivesCloseAndReopenWithoutChangingPayloadOrDestination(): Unit = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        var db = database(name)
        try {
            val config = ConnectionConfig("debug", "测试", "id")
            val frame = testFrame(config, UUID.randomUUID().toString(), SamplingWindow(0, 1_000), 10_000)
            var repo = ScannerRepository(RoomRecordStore(db), Gateway())
            repo.save(config, frame, "  餐厅 (1,2)  ")
            repo.saveInference(InferenceHistory(frame, null, "无模型"))
            val original = repo.samples.value.single()
            db.close(); db = database(name)
            repo = ScannerRepository(RoomRecordStore(db), Gateway()); repo.reload()
            assertEquals(original, repo.samples.value.single())
            assertEquals("餐厅 (1,2)", repo.samples.value.single().locationLabel)
            assertEquals(UploadStatus.LOCAL_ONLY, repo.samples.value.single().status)
            assertEquals(frame, repo.inferences.value.single().frame)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun debugCollectionPausesOnBackgroundAndRequiresManualResume(): Unit = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"; val db = database(name)
        val gateway = Gateway(); val collector = Collector(); val settings = Settings()
        val repo = ScannerRepository(RoomRecordStore(db), gateway)
        val holder = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ScannerViewModel(app, repo, settings, collector, 500).also { holder.put("test", it) } }
        try {
            connect(model, " DeBuG ")
            assertEquals(0, gateway.connects); assertFalse(model.state.value.inferenceReady)
            withContext(Dispatchers.Main) { model.location(" 餐厅 "); model.prepareCollection(); model.toggleCollection() }
            delay(100); assertTrue(repo.samples.value.isEmpty())
            withTimeout(5_000) { repo.samples.first { it.isNotEmpty() } }
            withContext(Dispatchers.Main) { model.setForeground(false) }
            delay(150)
            val count = repo.samples.value.size
            delay(650); assertEquals(count, repo.samples.value.size)
            assertFalse(model.state.value.running); assertFalse(collector.active)
            assertTrue(repo.samples.value.all { it.status == UploadStatus.LOCAL_ONLY })
            withContext(Dispatchers.Main) { model.setForeground(true) }
            delay(650); assertEquals(count, repo.samples.value.size)
            withContext(Dispatchers.Main) { model.startInference() }
            assertFalse(model.state.value.inferring); assertEquals(0, gateway.inferences)
            assertEquals(0, gateway.uploads)
            assertEquals("debug", settings.config.value.server)
            withContext(Dispatchers.Main) { model.clearError(); model.toggleCollection() }
            delay(100); assertEquals(count, repo.samples.value.size)
            withTimeout(5_000) { repo.samples.first { it.size > count } }
            Unit
        } finally {
            withContext(Dispatchers.Main) { model.setForeground(false); holder.clear() }
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun inferenceIsSingleFlightAndLateResultsAfterPauseAreDiscarded(): Unit = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"; val db = database(name)
        val gateway = Gateway(); val deferred = CompletableDeferred<Unit>(); gateway.heldResponse = deferred
        val repo = ScannerRepository(RoomRecordStore(db), gateway)
        val holder = ViewModelStore()
        val model = withContext(Dispatchers.Main) { ScannerViewModel(app, repo, Settings(), Collector(), 200).also { holder.put("test", it) } }
        try {
            connect(model, "http://test.invalid/")
            withContext(Dispatchers.Main) { model.startInference() }
            withTimeout(5_000) { while (gateway.inferences == 0) delay(20) }
            delay(650); assertEquals(1, gateway.inferences)
            withContext(Dispatchers.Main) { model.pause() }
            deferred.complete(Unit); delay(200)
            assertNull(model.state.value.inference); assertTrue(repo.inferences.value.isEmpty())
            assertTrue(repo.samples.value.isEmpty())
        } finally {
            deferred.complete(Unit)
            withContext(Dispatchers.Main) { model.setForeground(false); holder.clear() }
            db.close(); context.deleteDatabase(name)
        }
    }
}

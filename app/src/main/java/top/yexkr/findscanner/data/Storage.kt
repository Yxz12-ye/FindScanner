package top.yexkr.findscanner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private val Context.configDataStore by preferencesDataStore("connection")
interface ConnectionSettings {
    val config: Flow<ConnectionConfig>
    suspend fun save(config: ConnectionConfig)
}
class ConfigStore(context: Context) : ConnectionSettings {
    private val store = context.configDataStore
    private val key = stringPreferencesKey("config")
    override val config: Flow<ConnectionConfig> = store.data.map { prefs ->
        prefs[key]?.let { wireJson.decodeFromString<ConnectionConfig>(it) } ?: ConnectionConfig()
    }
    override suspend fun save(config: ConnectionConfig) { store.edit { it[key] = wireJson.encodeToString(config) } }
}

interface RecordStore {
    suspend fun samples(): List<SampleRecord>
    suspend fun insert(record: SampleRecord)
    suspend fun update(record: SampleRecord)
    suspend fun inferences(): List<InferenceHistory>
    suspend fun insertInference(history: InferenceHistory)
    suspend fun clear()
}
class RoomRecordStore(private val database: ScannerDatabase) : RecordStore {
    private val dao get() = database.dao()
    override suspend fun samples() = withContext(Dispatchers.IO) { dao.samples().map {
        SampleRecord(it.id, it.server, it.payload, it.createdAt, UploadStatus.valueOf(it.status), it.attempts, it.nextRetryAt, it.error)
    } }
    override suspend fun insert(record: SampleRecord) = withContext(Dispatchers.IO) {
        dao.insert(SampleEntity().apply {
            id = record.id; server = record.server; payload = record.payload; status = record.status.name
            createdAt = record.createdAt; attempts = record.attempts; nextRetryAt = record.nextRetryAt; error = record.error
        })
    }
    override suspend fun update(record: SampleRecord) = withContext(Dispatchers.IO) {
        dao.update(record.id, record.status.name, record.attempts, record.nextRetryAt, record.error)
    }
    override suspend fun inferences() = withContext(Dispatchers.IO) {
        dao.inferences().map { wireJson.decodeFromString<InferenceHistory>(it.payload) }
    }
    override suspend fun insertInference(history: InferenceHistory) = withContext(Dispatchers.IO) {
        dao.insertInference(InferenceEntity().apply {
            id = history.frame.id; createdAt = history.frame.windowEndedAt; payload = wireJson.encodeToString(history)
        })
    }
    override suspend fun clear() = withContext(Dispatchers.IO) {
        database.runInTransaction { dao.clearSamples(); dao.clearInferences() }
    }
}

package top.yexkr.findscanner

import android.app.Application
import androidx.room.Room
import top.yexkr.findscanner.data.*

class ScannerApplication : Application() {
    val configStore by lazy { ConfigStore(this) }
    val repository by lazy {
        val database = Room.databaseBuilder(this, ScannerDatabase::class.java, "scanner.db").build()
        ScannerRepository(RoomRecordStore(database), ApiGateway(BuildConfig.VERSION_NAME))
    }
}

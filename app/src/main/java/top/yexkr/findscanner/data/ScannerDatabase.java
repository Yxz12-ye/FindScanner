package top.yexkr.findscanner.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;

// Java entities use the standard annotation processor, avoiding a Kotlin/KSP version dependency.
@Database(entities = {SampleEntity.class, InferenceEntity.class}, version = 1, exportSchema = true)
public abstract class ScannerDatabase extends RoomDatabase {
    public abstract ScannerDao dao();
}

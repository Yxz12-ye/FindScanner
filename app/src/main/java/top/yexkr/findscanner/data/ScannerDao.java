package top.yexkr.findscanner.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.OnConflictStrategy;
import java.util.List;

@Dao
public interface ScannerDao {
    @Query("SELECT * FROM samples ORDER BY createdAt DESC, id DESC") List<SampleEntity> samples();
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(SampleEntity sample);
    @Query("UPDATE samples SET status=:status, attempts=:attempts, nextRetryAt=:next, error=:error WHERE id=:id")
    void update(String id, String status, int attempts, long next, String error);
    @Query("SELECT * FROM inferences ORDER BY createdAt DESC, id DESC") List<InferenceEntity> inferences();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insertInference(InferenceEntity inference);
    @Query("DELETE FROM samples") void clearSamples();
    @Query("DELETE FROM inferences") void clearInferences();
}

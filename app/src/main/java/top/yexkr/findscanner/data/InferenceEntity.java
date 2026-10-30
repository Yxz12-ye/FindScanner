package top.yexkr.findscanner.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "inferences")
public class InferenceEntity {
    @PrimaryKey @NonNull public String id = "";
    public long createdAt;
    @NonNull public String payload = "";
}

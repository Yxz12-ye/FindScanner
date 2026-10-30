package top.yexkr.findscanner.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Index;

@Entity(tableName = "samples", indices = {@Index("createdAt")})
public class SampleEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String server = "";
    @NonNull public String payload = "";
    @NonNull public String status = "PENDING";
    public long createdAt;
    public int attempts;
    public long nextRetryAt;
    public String error;
}

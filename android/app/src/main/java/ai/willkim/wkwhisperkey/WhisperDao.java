package ai.willkim.wkwhisperkey;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface WhisperDao {
    @Insert
    void insert(WhisperData data);

    @Query("SELECT * FROM whisper_data ORDER BY id DESC LIMIT :n")
    List<WhisperData> sample(int n);
}

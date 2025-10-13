package ai.willkim.wkwhisperkey;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "whisper_data")
public class WhisperData {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public byte[] wave;
    public String text;

    public WhisperData(byte[] wave, String text) {
        this.wave = wave;
        this.text = text;
    }
}

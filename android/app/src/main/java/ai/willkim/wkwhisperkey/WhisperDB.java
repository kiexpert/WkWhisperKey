package ai.willkim.wkwhisperkey;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

// Room Database definition
@Database(entities = {WhisperData.class}, version = 1, exportSchema = false)
public abstract class WhisperDB extends RoomDatabase {

    // DAO accessor
    public abstract WhisperDao dao();

    // Singleton instance
    private static volatile WhisperDB INSTANCE;

    public static WhisperDB get(Context context) {
        if (INSTANCE == null) {
            synchronized (WhisperDB.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            WhisperDB.class,
                            "whisper.db"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}

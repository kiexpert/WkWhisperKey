package ai.willkim.wkwhisperkey;

import android.content.Context;

import androidx.room.Room;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WhisperStore {
    private static WhisperDB db;
    private static final ExecutorService ioPool = Executors.newSingleThreadExecutor();

    private static WhisperDB getDB(Context ctx) {
        if (db == null)
            db = Room.databaseBuilder(ctx, WhisperDB.class, "whisper.db").build();
        return db;
    }

    public static void insert(byte[] wave, String text) {
        ioPool.execute(() -> {
            WhisperData data = new WhisperData(0, wave, text);
            getDB(AppContext.get()).dao().insert(data);
        });
    }

    public static List<WhisperData> sampleBatch(int n) {
        return getDB(AppContext.get()).dao().sample(n);
    }
}

package ai.willkim.wkwhisperkey;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;
import androidx.work.OneTimeWorkRequest;

import java.util.List;

public class FineTuneWorker extends Worker {

    public FineTuneWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        List<WhisperData> samples = WhisperStore.sampleBatch(8);
        for (WhisperData data : samples) {
            // pass data.wave, data.text
        }
        new WhisperEngine().finetune(samples);
        return Result.success();
    }

    public static void enqueue(Context ctx) {
        WorkManager.getInstance(ctx)
                .enqueue(new OneTimeWorkRequest.Builder(FineTuneWorker.class).build());
    }
}

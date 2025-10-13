package ai.willkim.wkwhisperkey.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.willkim.wkwhisperkey.data.WhisperStore
import ai.willkim.wkwhisperkey.engine.WhisperEngine

/**
 * FineTuneWorker.kt
 * 미세조정(파인튜닝) 백그라운드 워커
 * Kotlin 1.9.25 / WorkManager 2.9.x 호환
 */

class FineTuneWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val samples = WhisperStore.sampleBatch(applicationContext, 8)
            if (samples.isNotEmpty()) {
                val pairs = samples.map { it.wave to it.text }
                WhisperEngine().finetune(pairs)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    companion object {
        fun enqueue(ctx: Context) {
            val work = OneTimeWorkRequestBuilder<FineTuneWorker>().build()
            WorkManager.getInstance(ctx).enqueue(work)
        }
    }
}

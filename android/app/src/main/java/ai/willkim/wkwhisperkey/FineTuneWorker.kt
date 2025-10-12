class FineTuneWorker(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val samples = WhisperStore.sampleBatch(8)
        val pairs = samples.map { it.wave to it.text }
        WhisperEngine().finetune(pairs)
        return Result.success()
    }

    companion object {
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(
                OneTimeWorkRequestBuilder<FineTuneWorker>().build()
            )
        }
    }
}

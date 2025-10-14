package ai.willkim.wkwhisperkey.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

object WkAudioInput {
    private var record: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val channelFlow = MutableSharedFlow<FloatArray>(extraBufferCapacity = 2)

    private const val SAMPLE_RATE = 16000
    private const val BUFFER_SIZE = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun start() {
        if (record != null) return
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )
        record?.startRecording()
        scope.launch {
            val buf = ShortArray(BUFFER_SIZE)
            while (isActive) {
                val len = record?.read(buf, 0, buf.size) ?: 0
                if (len > 0) {
                    val floats = FloatArray(len) { i -> buf[i] / 32768f }
                    channelFlow.emit(floats)
                }
            }
        }
    }

    fun stop() {
        record?.stop()
        record?.release()
        record = null
        scope.coroutineContext.cancelChildren()
    }
}

package ai.willkim.wkwhisperkey.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class WkAudioCapture(
    context: Context,
    private val onBuffer: (ShortArray) -> Unit
) {
    private val rate = 16000
    private val bufSize = AudioRecord.getMinBufferSize(
        rate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        rate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufSize
    )

    private var running = false
    fun start() {
        if (running) return
        running = true
        recorder.startRecording()
        Thread {
            val buf = ShortArray(bufSize)
            while (running) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) onBuffer(buf.copyOf(read))
            }
        }.start()
    }

    fun stop() { running = false; recorder.stop() }
    fun release() { recorder.release() }
}

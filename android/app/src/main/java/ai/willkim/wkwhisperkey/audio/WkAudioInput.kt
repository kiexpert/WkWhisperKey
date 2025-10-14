package ai.willkim.wkwhisperkey.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class WkAudioInput {
    private val sampleRate = 16000  // const val 제거 (컴파일 오류 방지)
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private var recorder: AudioRecord? = null

    fun startRecording(onBuffer: (ShortArray) -> Unit) {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        recorder?.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize)
            while (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) onBuffer(buffer.copyOf(read))
            }
        }.start()
    }

    fun stop() {
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}

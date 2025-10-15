package ai.willkim.wkwhisperkey.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.background
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

class WkAudioInput {
    private var recorder: AudioRecord? = null
    private val bufferSize = AudioRecord.getMinBufferSize(
        16000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val _energy = MutableStateFlow(0f)
    val energy: StateFlow<Float> = _energy

    private var job: Job? = null

    fun start() {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    
        if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
            recorder?.startRecording()
        }
    
        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(bufferSize)
            while (isActive) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (false && read > 0 && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val rms = sqrt(buffer.take(read).map { it * it.toFloat() }.average()).toFloat()
                    _energy.value = rms / 1000f
                }
                if (read > 0) {
                    val rms = sqrt(buffer.take(read).map { it * it.toFloat() }.average()).toFloat()
                    _energy.value = rms / 1000f
                    Log.i("AudioEnergy", "RMS=$rms, normalized=${_energy.value}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}

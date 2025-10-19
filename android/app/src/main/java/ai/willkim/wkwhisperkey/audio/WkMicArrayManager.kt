package ai.willkim.wkwhisperkey.audio

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

class WkMicArrayManager(
    private val context: Context,
    private val onBuffer: (Int, ShortArray) -> Unit,
    private val onEnergyLevel: (Int, Float) -> Unit
) {
    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private val bufferSize = AudioRecord.getMinBufferSize(
        44100,
        AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private val sampleRate = 44100
    private var isRunning = false

    /** 🎧 기본 스테레오 마이크 장치 탐색 */
    fun scanInputs(): List<AudioDeviceInfo> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val mics = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        Log.i("WkMicArray", "🎙️ Found ${mics.size} input devices")
        return mics
    }

    /** 🟢 스테레오 마이크 시작 (좌우 채널 동시 수집) */
    fun startStereo() {
        stopAll()
        try {
            val stereoFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC) // ✅ UNPROCESSED → MIC (보안 완화)
                .setAudioFormat(stereoFormat)
                .build()

            audioRecord?.startRecording()
            isRunning = true
            Log.i("WkMicArray", "✅ Stereo mic started")

            job = CoroutineScope(Dispatchers.Default).launch {
                val buffer = ShortArray(bufferSize)
                while (isActive && isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        onBuffer(0, buffer)
                        val energy = calculateStereoEnergy(buffer, read)
                        onEnergyLevel(0, energy)
                    } else {
                        Log.w("WkMicArray", "⚠️ Read returned $read samples")
                        delay(200)
                    }
                }
                Log.w("WkMicArray", "🛑 Stereo mic loop stopped")
            }

        } catch (e: Exception) {
            Log.e("WkMicArray", "❌ Stereo start failed: ${e.message}")
            isRunning = false
        }
    }

    /** 🔴 정지 및 자원 해제 */
    fun stopAll() {
        isRunning = false
        job?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i("WkMicArray", "🧹 Mic resources released")
    }

    /** 🎚️ 에너지 계산 (좌우 평균 RMS) */
    private fun calculateStereoEnergy(buffer: ShortArray, read: Int): Float {
        if (read < 4) return 0f
        var leftSum = 0.0
        var rightSum = 0.0
        var count = 0
        var i = 0
        while (i < read - 1) {
            leftSum += buffer[i].toDouble() * buffer[i]
            rightSum += buffer[i + 1].toDouble() * buffer[i + 1]
            count++
            i += 2
        }
        val rmsL = sqrt(leftSum / count)
        val rmsR = sqrt(rightSum / count)
        val norm = (rmsL + rmsR) / 32768.0
        return norm.toFloat().coerceIn(0f, 1f)
    }
}

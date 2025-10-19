package ai.willkim.wkwhisperkey.audio

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioManager.*
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.*
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.sqrt
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor

/**
 * WkMicArrayManager v3.0
 * -----------------------
 * Single AudioRecord / Dual-channel capture (STEREO)
 * Fold5: top+bottom mic 동시 입력용
 */
class WkMicArrayManager(
    private val context: Context,
    private val onBuffer: (id: Int, data: ShortArray) -> Unit,
    private val onEnergyLevel: ((id: Int, level: Float) -> Unit)? = null
) {
    private var recorder: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastLeft: ShortArray = shortArrayOf()
    private var lastRight: ShortArray = shortArrayOf()

    fun startStereo(sampleRate: Int = 48000) {
        stopAll()

        try {
            val bufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .build()

            val rec = recorder ?: return
            scope.launch { captureLoop(rec, bufSize) }
            Log.i("MicArray", "🎧 Stereo mic capture started (48kHz)")
        } catch (e: Exception) {
            Log.e("MicArray", "init fail: ${e.message}")
        }
    }

    private suspend fun captureLoop(rec: AudioRecord, bufSize: Int) {
        val buf = ShortArray(bufSize)
        try {
            rec.startRecording()
            while (scope.isActive) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    // 분리: L, R
                    val left = ShortArray(read / 2)
                    val right = ShortArray(read / 2)
                    var li = 0
                    var ri = 0
                    for (i in 0 until read step 2) {
                        left[li++] = buf[i]
                        if (i + 1 < read) right[ri++] = buf[i + 1]
                    }

                    lastLeft = left
                    lastRight = right

                    // 각각 RMS 계산
                    val energyL = calcEnergy(left)
                    val energyR = calcEnergy(right)
                    val mergedEnergy = ((energyL + energyR) / 2f).coerceIn(0f, 1f)

                    // 콜백 (L/R 개별 버퍼도 구분 가능)
                    onBuffer(0, left)
                    onBuffer(1, right)
                    onEnergyLevel?.invoke(0, mergedEnergy)
                }
                WkSafetyMonitor.heartbeat()
            }
        } catch (e: Exception) {
            Log.e("MicArray", "loop error: ${e.message}")
        } finally {
            try { rec.stop(); rec.release() } catch (_: Exception) {}
        }
    }

    private fun calcEnergy(buf: ShortArray): Float {
        var sum = 0.0
        for (s in buf) sum += s * s
        val rms = sqrt(sum / buf.size)
        return (rms / 32768.0).toFloat()
    }

    fun stopAll() {
        scope.cancel()
        recorder?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        recorder = null
        Log.i("MicArray", "Stereo mic stopped.")
    }

    fun getLastBuffer(id: Int = 0): ShortArray? {
        return if (id == 0) lastLeft else lastRight
    }

    fun scanInputs(): List<AudioDeviceInfo> {
        val am = context.getSystemService(AudioManager::class.java)
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        Log.i("WkMicArray", "🔍 found ${inputs.size} input devices")
        return inputs
    }

    val deviceFilter = IntentFilter(AudioManager.ACTION_HEADSET_PLUG).apply {
        addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
    }
    val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == AudioManager.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", -1)
                val msg = if (state == 1) "🎧 외부 마이크 연결됨" else "🔌 외부 마이크 해제됨"
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                Log.i("MicArray", msg)
            } else if (action == AudioManager.ACTION_MICROPHONE_MUTE_CHANGED) {
                val muted = intent.getBooleanExtra("android.media.extra.MICROPHONE_MUTE", false)
                val msg = if (muted) "🔇 마이크 음소거됨" else "🎙️ 마이크 활성화"
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                Log.i("MicArray", msg)
            }
        }
    }
}

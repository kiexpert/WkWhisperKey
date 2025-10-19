package ai.willkim.wkwhisperkey.audio

import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

class WkMicArrayManager(
    private val context: android.content.Context,
    private val onBuffer: (Int, ShortArray) -> Unit,
    private val onEnergyLevel: (Int, Float) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeRecord: AudioRecord? = null
    private var running = false

    /** 모든 입력 장치 목록 탐색 */
    fun scanInputs(): List<AudioDeviceInfo> {
        val mgr = context.getSystemService(AudioManager::class.java)
        val inputs = mgr?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList() ?: emptyList()
        Log.i("MicArray", "🎧 Found ${inputs.size} input devices")
        return inputs
    }

    /** 폴드5 안전모드: 하나씩만 순차적으로 시도 */
    fun startSequential(devices: List<AudioDeviceInfo>) {
        scope.launch {
            running = true
            for (dev in devices) {
                if (!running) break
                try {
                    startMic(dev)
                    delay(4000L) // 4초간 수집 후 다음 장치로 전환
                    stopMic()
                } catch (e: Exception) {
                    Log.e("MicArray", "❌ Device ${dev.id} start failed: ${e.message}")
                }
                delay(1000L) // 1초 간격
            }
            Log.i("MicArray", "✅ Sequential mic scan complete")
        }
    }

    /** 단일 마이크 녹음 시작 */
    private fun startMic(device: AudioDeviceInfo) {
        Log.i("MicArray", "🎤 Trying ${device.id} (${device.productName})")
        val rate = 16000
        val bufSize = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        // ⚙️ 보안우회: MIC 로 변경
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(bufSize * 2)
            .setAudioDevice(device)
            .build()

        activeRecord = rec
        rec.startRecording()

        Log.i("MicArray", "✅ Started Mic ${device.id} ${device.productName}")
        scope.launch { readLoop(device.id, rec) }
    }

    private suspend fun readLoop(id: Int, rec: AudioRecord) {
        val buf = ShortArray(2048)
        while (running) {
            val read = rec.read(buf, 0, buf.size)
            if (read <= 0) {
                Log.w("MicArray", "⚠️ Mic $id returned $read samples")
                delay(200)
                continue
            }
            onBuffer(id, buf)
            val energy = sqrt(buf.take(read).sumOf { (it * it).toDouble() } / read).toFloat() / 32768f
            onEnergyLevel(id, energy)
        }
    }

    fun stopMic() {
        activeRecord?.apply {
            try {
                stop()
                release()
                Log.i("MicArray", "🛑 Mic stopped and released")
            } catch (_: Exception) {}
        }
        activeRecord = null
    }

    fun stopAll() {
        running = false
        stopMic()
        scope.cancel()
    }
}

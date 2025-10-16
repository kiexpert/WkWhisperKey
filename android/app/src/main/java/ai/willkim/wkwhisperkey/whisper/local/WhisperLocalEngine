package ai.willkim.wkwhisperkey.whisper.local

import ai.willkim.wkwhisperkey.whisper.WhisperEngineInterface
import android.util.Log
import java.nio.ByteBuffer

/**
 * WhisperLocalEngine
 * ------------------
 * Kotlin + ggml 기반 CPU 전용 Whisper wrapper (로컬 모델)
 * - whisper.cpp 포맷 모델(.bin) 로드
 */
class WhisperLocalEngine : WhisperEngineInterface {

    private var model: LocalWhisper? = null
    private val correctionMap = mutableMapOf<String, String>()

    init {
        try {
            model = LocalWhisper.loadModel("/sdcard/whisper/ggml-base.bin")
            Log.i("WhisperLocal", "Model loaded.")
        } catch (e: Exception) {
            Log.e("WhisperLocal", "Model load failed: ${e.message}")
        }
    }

    fun addCorrection(wrong: String, right: String) {
        correctionMap[wrong] = right
    }

    override fun transcribe(pcmBuffer: ByteBuffer): String? {
        val raw = try {
            model?.transcribe(pcmBuffer)
        } catch (e: Exception) {
            Log.e("WhisperLocal", "Transcribe failed: ${e.message}")
            null
        } ?: return null

        // 교정 사전 적용
        return correctionMap.entries.fold(raw) { acc, (wrong, right) ->
            acc.replace(wrong, right)
        }
    }
}

/**
 * LocalWhisper
 * -------------
 * 로컬 CPU용 단순 Mock 클래스 (실제 ggml 연동 필요)
 */
class LocalWhisper {
    companion object {
        fun loadModel(path: String): LocalWhisper {
            Log.i("LocalWhisper", "loadModel: $path")
            // 실제 모델 로더로 교체 필요
            return LocalWhisper()
        }
    }

    fun transcribe(pcm: ByteBuffer): String {
        // 실제 whisper.cpp CPU 호출 또는 JNI 교체 필요
        return "[local whisper result]"
    }
}

package ai.willkim.wkwhisperkey.whisper.native

import ai.willkim.wkwhisperkey.whisper.WhisperEngineInterface
import android.util.Log
import java.nio.ByteBuffer

/**
 * WhisperCppEngine
 * ----------------
 * JNI 기반 whisper.cpp 엔진 연동 (libwhisper.so 필요)
 */
class WhisperCppEngine : WhisperEngineInterface {

    companion object {
        init {
            try {
                System.loadLibrary("whisper")
                Log.i("WhisperCpp", "libwhisper.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("WhisperCpp", "Native lib load failed: ${e.message}")
            }
        }
    }

    external fun nativeTranscribe(buffer: ByteBuffer, len: Int): String?

    override fun transcribe(pcmBuffer: ByteBuffer): String? {
        return try {
            val len = pcmBuffer.remaining()
            nativeTranscribe(pcmBuffer, len)
        } catch (e: Exception) {
            Log.e("WhisperCpp", "transcribe error: ${e.message}")
            null
        }
    }
}

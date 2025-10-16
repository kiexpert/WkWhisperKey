package ai.willkim.wkwhisperkey.whisper

import java.nio.ByteBuffer

/**
 * WhisperEngineInterface
 * ----------------------
 * 모든 Whisper 엔진 구현체의 공통 인터페이스.
 */
interface WhisperEngineInterface {
    /** PCM(ByteBuffer, 16kHz 16bit mono) → 텍스트 변환 */
    fun transcribe(pcmBuffer: ByteBuffer): String?
}

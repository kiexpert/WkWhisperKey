package ai.willkim.wkwhisperkey.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ShortArray → ByteBuffer 변환 확장 함수 */
fun ShortArray.toByteBuffer(): ByteBuffer {
    val bb = ByteBuffer.allocate(this.size * 2)
    bb.order(ByteOrder.LITTLE_ENDIAN)
    forEach { bb.putShort(it) }
    bb.flip()
    return bb
}

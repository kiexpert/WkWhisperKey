package ai.willkim.wkwhisperkey.core

object WkIntentRouter {
    private val buffers = mutableMapOf<Int, FloatArray>()

    fun routeAudio(id: Int, l: FloatArray, c: FloatArray, r: FloatArray) {
        val merged = FloatArray(c.size) { i ->
            (l.getOrNull(i) ?: 0f) * 0.3f +
            (c.getOrNull(i) ?: 0f) * 0.4f +
            (r.getOrNull(i) ?: 0f) * 0.3f
        }
        buffers[id] = merged
    }

    fun getSpeakerAudio(id: Int): FloatArray = buffers[id] ?: FloatArray(0)
}

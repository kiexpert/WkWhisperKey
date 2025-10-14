package ai.willkim.wkwhisperkey.core

import kotlin.math.*

object WkSpatialSeparator {
    // 간단한 위상차 기반 화자 추정
    fun identifySpeaker(left: FloatArray, center: FloatArray, right: FloatArray): Int {
        val eL = energy(left)
        val eC = energy(center)
        val eR = energy(right)
        return when {
            eL > eC && eL > eR -> 0
            eR > eC && eR > eL -> 2
            else -> 1
        }
    }

    private fun energy(buf: FloatArray): Float {
        var e = 0f
        for (v in buf) e += v * v
        return e / buf.size
    }
}

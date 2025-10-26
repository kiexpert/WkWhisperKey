package ai.willkim.wkwhisperkey.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class WkBitwisePhaseSeparatorTest {

    private val separator = WkBitwisePhaseSeparatorShard.instance
    private val sampleRate = 44100
    private val N = 2048

    internal object WkIntFFT {
        fun fftInt(samples: IntArray): Pair<DoubleArray, DoubleArray> {
            val n = samples.size
            val re = DoubleArray(n) { samples[it].toDouble() }
            val im = DoubleArray(n)
            var j = 0
            for (i in 1 until n - 1) {
                var bit = n shr 1
                while (j >= bit) { j -= bit; bit = bit shr 1 }
                j += bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * Math.PI / len
                val wlenRe = cos(ang)
                val wlenIm = sin(ang)
                for (i in 0 until n step len) {
                    var wRe = 1.0
                    var wIm = 0.0
                    for (k in 0 until len / 2) {
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + len / 2] * wRe - im[i + k + len / 2] * wIm
                        val vIm = re[i + k + len / 2] * wIm + im[i + k + len / 2] * wRe
                        re[i + k] = uRe + vRe
                        im[i + k] = uIm + vIm
                        re[i + k + len / 2] = uRe - vRe
                        im[i + k + len / 2] = uIm - vIm
                        val tmpRe = wRe * wlenRe - wIm * wlenIm
                        val tmpIm = wRe * wlenIm + wIm * wlenRe
                        wRe = tmpRe; wIm = tmpIm
                    }
                }
                len = len shl 1
            }
            val mag = DoubleArray(n) { sqrt(re[it] * re[it] + im[it] * im[it]) }
            val phase = DoubleArray(n) { atan2(im[it], re[it]) }
            return mag to phase
        }
    }

    @Test
    fun `SIN 테이블이 0~2π 구간에서 정확히 동작해야 함`() {
        val errors = mutableListOf<Double>()
        for (i in 0 until 4096 step 128) {
            val expected = sin((i.toDouble() / 4096) * 2 * Math.PI)
            val actual = WkBitwisePhaseSeparator.Companion.run {
                SIN_TABLE[i] / Short.MAX_VALUE.toDouble()
            }
            errors += abs(expected - actual)
        }
        assertTrue(errors.average() < 1e-3, "sin 테이블 오차가 너무 큼")
    }

    @Test
    fun `단일 사인파 DFT 결과가 주파수별로 최대여야 함`() {
        val freq = 700
        val samples = IntArray(N) {
            (sin(2 * Math.PI * freq * it / sampleRate) * Short.MAX_VALUE).toInt()
        }

        val method = separator::class.java.getDeclaredMethod("dft8", IntArray::class.java)
        method.isAccessible = true
        val result = method.invoke(separator, samples)
        require(result is Pair<*, *>) { "dft8() 반환형이 Pair가 아닙니다" }

        @Suppress("UNCHECKED_CAST")
        val pair = result as Pair<IntArray, IntArray>
        val mag = pair.first
        val phase = pair.second

        val maxIdx = mag.indices.maxByOrNull { mag[it] }!!
        assertTrue(mag[maxIdx] > 1000, "Amplitude too small for main band")
        assertTrue(phase[maxIdx] in 0 until 4096, "Phase out of range")
    }

    @Test
    fun `FFT와 DFT가 유사한 진폭 결과를 보여야 함`() {
        val freq = 1100
        val samples = IntArray(N) {
            (sin(2 * Math.PI * freq * it / sampleRate) * 16000).toInt()
        }

        val method = separator::class.java.getDeclaredMethod("dft8", IntArray::class.java)
        method.isAccessible = true
        val result = method.invoke(separator, samples)
        require(result is Pair<*, *>) { "dft8() 반환형이 Pair가 아닙니다" }

        @Suppress("UNCHECKED_CAST")
        val pair = result as Pair<IntArray, IntArray>
        val magDFT = pair.first

        val (magFFT, _) = WkIntFFT.fftInt(samples)
        val dftPower = sqrt(magDFT.sumOf { it * it.toDouble() } / magDFT.size)
        val fftPower = sqrt(magFFT.sumOf { it * it } / magFFT.size)
        val ratio = dftPower / fftPower
        assertTrue(ratio in 0.3..3.0, "FFT/DFT 진폭 비율 불일치 ($ratio)")
    }

    @Test
    fun `위상정합 함수는 입 반지름 허용범위 내에서만 true를 반환해야 함`() {
        val f = WkBitwisePhaseSeparator.Companion
        val λ = 63
        val mouthBits = 8
        //assertTrue(f.isPhaseMatchedInt(10, 10, λ, mouthBits))
        assertTrue(f.isPhaseMatchedInt(λ, 0, λ, mouthBits))
        assertTrue(f.isPhaseMatchedInt(-λ, 0, λ, mouthBits))
        assertFalse(f.isPhaseMatchedInt(3 * λ + 10, 0, λ, mouthBits))
        assertFalse(f.isPhaseMatchedInt(100, 10, λ, mouthBits))
    }

    @Test
    fun `전체 분리 파이프라인이 크래시 없이 동작해야 함`() {
        val freq = 1700
        val durSamples = 4096
        val L = ShortArray(durSamples) {
            (sin(2 * Math.PI * freq * it / sampleRate) * 16000).toInt().toShort()
        }
        val R = ShortArray(durSamples) {
            (sin(2 * Math.PI * freq * (it - 5) / sampleRate) * 16000).toInt().toShort()
        }

        val result = separator.separate(L, R)
        assertNotNull(result)
        val keys = separator.getActiveKeys()
        if (keys.isNotEmpty()) {
            val k = keys.first()
            println("Δ=${k.deltaIndex}  f=${k.freq}  d=${k.distanceMm}")
            assertTrue(k.distanceMm >= 0)
        }
    }
}

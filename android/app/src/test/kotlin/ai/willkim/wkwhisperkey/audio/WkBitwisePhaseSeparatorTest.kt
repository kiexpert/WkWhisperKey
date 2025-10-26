package ai.willkim.wkwhisperkey.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class WkBitwisePhaseSeparatorTest {

    private val separator = WkBitwisePhaseSeparatorShard.instance
    private val sampleRate = 44100
    private val N = 2048

    // ------------------------------------------------------------
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
        assertTrue(errors.average() < 1e-3, "sin 테이블 오차 과다")
    }

    // ------------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    private fun invokeDFT(samples: IntArray): Pair<IntArray, IntArray> {
        val method = separator::class.java.getDeclaredMethod("dft8", IntArray::class.java)
        method.isAccessible = true
        return method.invoke(separator, samples) as Pair<IntArray, IntArray>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeFFT(samples: IntArray): Pair<IntArray, IntArray> {
        val fftMethod = separator::class.java.getDeclaredMethod(
            "fftInt", IntArray::class.java, IntArray::class.java, Int::class.java
        )
        fftMethod.isAccessible = true
        val re = samples.clone()
        val im = IntArray(re.size)
        fftMethod.invoke(separator, re, im, 15)
        return re to im
    }

    // ------------------------------------------------------------
    @Test
    fun `단일 사인파 DFT 결과가 주파수별로 최대여야 함`() {
        val freq = 700
        val samples = IntArray(N) {
            (sin(2 * Math.PI * freq * it / sampleRate) * Short.MAX_VALUE).toInt()
        }
        val (mag, phase) = invokeDFT(samples)

        val maxIdx = mag.indices.maxByOrNull { mag[it] }!!
        val expectedBand = separator.javaClass
            .getDeclaredField("bands").apply { isAccessible = true }
            .get(separator) as IntArray

        assertEquals(expectedBand[maxIdx], 700, "DFT peak band mismatch")
        assertTrue(mag[maxIdx] > 1000, "Amplitude too small for main band")
        assertTrue(phase[maxIdx] in 0 until 4096, "Phase out of range")
    }

    // ------------------------------------------------------------
    @Test
    fun `FFT와 DFT가 유사한 진폭 결과를 보여야 함`() {
        val freq = 1100
        val samples = IntArray(N) {
            (sin(2 * Math.PI * freq * it / sampleRate) * 16000).toInt()
        }
        val (magDFT, _) = invokeDFT(samples)
        val (re, _) = invokeFFT(samples)

        val powerFFT = sqrt(re.sumOf { it * it.toDouble() } / re.size)
        val powerDFT = sqrt(magDFT.sumOf { it * it.toDouble() } / magDFT.size)
        val ratio = powerDFT / powerFFT
        assertTrue(ratio in 0.5..2.0, "FFT/DFT 진폭 비율 불일치 ($ratio)")
    }

    // ------------------------------------------------------------
    @Test
    fun `위상정합 함수는 입 반지름 허용범위 내에서만 true를 반환해야 함`() {
        val f = WkBitwisePhaseSeparator.Companion
        val λ = 63
        val mouthBits = 1 // 절대정합 모드

        assertTrue(f.isPhaseMatchedInt(10, 10, λ, mouthBits))
        assertTrue(f.isPhaseMatchedInt(λ, 0, λ, mouthBits))
        assertFalse(f.isPhaseMatchedInt(10, 11, λ, mouthBits))
        assertFalse(f.isPhaseMatchedInt(3 * λ + 10, 0, λ, mouthBits))
    }

    // ------------------------------------------------------------
    @Test
    fun `전체 파이프라인이 크래시 없이 동작해야 함`() {
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

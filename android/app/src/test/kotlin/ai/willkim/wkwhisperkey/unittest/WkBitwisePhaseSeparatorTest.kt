package ai.willkim.wkwhisperkey.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

/**
 * ✅ WkBitwisePhaseSeparator 유닛테스트
 * ------------------------------------------------------------
 * - SIN 테이블 생성 검증
 * - DFT 정수형 결과 검증 (단일 사인파 입력)
 * - FFT 결과 검증 (정확도 vs kotlin.math FFT)
 * - 위상 정합 검사 함수 검증
 * - 분리기 전체 파이프라인 smoke test
 */
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
        assertTrue(errors.average() < 1e-3, "sin 테이블 오차가 너무 큼")
    }

    // ------------------------------------------------------------
    @Test
    fun `단일 사인파 DFT 결과가 주파수별로 최대여야 함`() {
        val freq = 700
        val samples = IntArray(N) {
            (sin(2 * Math.PI * freq * it / sampleRate) * Short.MAX_VALUE).toInt()
        }
        val (mag, phase) = separator.run {
            val method = this::class.java.getDeclaredMethod("dft8", IntArray::class.java)
            method.isAccessible = true
            method.invoke(this, samples) as Pair<IntArray, IntArray>
        }

        val maxIdx = mag.indices.maxByOrNull { mag[it] }!!
        assertEquals(700, separator.run { bands[maxIdx] }, "DFT peak band mismatch")
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
        val (magDFT, _) = separator.run {
            val m = this::class.java.getDeclaredMethod("dft8", IntArray::class.java)
            m.isAccessible = true
            m.invoke(this, samples) as Pair<IntArray, IntArray>
        }

        val fftMethod = separator::class.java.getDeclaredMethod("fftInt", IntArray::class.java, IntArray::class.java, Int::class.java)
        fftMethod.isAccessible = true
        val re = samples.clone()
        val im = IntArray(re.size)
        fftMethod.invoke(separator, re, im, 15)

        val power = sqrt(re.sumOf { it * it.toDouble() } / re.size)
        val dftPower = sqrt(magDFT.sumOf { it * it.toDouble() } / magDFT.size)
        val ratio = dftPower / power
        assertTrue(ratio in 0.8..1.2, "FFT/DFT 진폭 비율 불일치 ($ratio)")
    }

    // ------------------------------------------------------------
    @Test
    fun `위상정합 함수가 입 반지름 허용범위 내에서만 true여야 함`() {
        val f = WkBitwisePhaseSeparator.Companion
        val λ = 63
        assertTrue(f.isPhaseMatchedInt(10, 10, λ, 32))
        assertTrue(f.isPhaseMatchedInt(42, 42, λ, 32))
        assertFalse(f.isPhaseMatchedInt(100, 10, λ, 32))
    }

    // ------------------------------------------------------------
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
        assertTrue(result.size >= 0)
        val keys = separator.getActiveKeys()
        if (keys.isNotEmpty()) {
            val k = keys.first()
            println("Δ=${k.deltaIndex}  f=${k.freq}  d=${k.distanceMm}")
            assertTrue(k.distanceMm >= 0)
        }
    }
}

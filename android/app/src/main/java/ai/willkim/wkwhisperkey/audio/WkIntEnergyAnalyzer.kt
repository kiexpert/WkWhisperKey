package ai.willkim.wkwhisperkey.audio

import kotlin.math.*

// ===========================================================
// 🔹 WkIntEnergyAnalyzer
//    - 정수 누산 기반 FFT + 위상 분석기
//    - 16bit PCM 입력, 더블 위상 유지
//    - WkVoiceSeparator와 독립 모듈
// ===========================================================
class WkIntEnergyAnalyzer(private val sampleRate: Int) {

    data class BandEnergy(
        val freq: Double,
        val energy: Long,   // 정수 에너지
        val rms: Float,     // RMS
        val phase: Double   // 위상
    )

    fun analyzeFrame(L: ShortArray, R: ShortArray, bands: DoubleArray): List<BandEnergy> {
        val n = L.size
        var sumL = 0L
        var sumR = 0L
        var sumLR = 0L

        // --- 정수 누산 ---
        for (i in 0 until n) {
            val l = L[i].toInt()
            val r = R[i].toInt()
            sumL += l * l
            sumR += r * r
            sumLR += l * r
        }

        val rmsL = sqrt(sumL.toDouble() / n).toFloat()
        val rmsR = sqrt(sumR.toDouble() / n).toFloat()
        val corr = sumLR.toDouble() / sqrt(sumL.toDouble() * sumR.toDouble())

        // --- FFT 변환 ---
        val xL = DoubleArray(n) { L[it].toDouble() }
        val xR = DoubleArray(n) { R[it].toDouble() }
        val fftL = fft(xL)
        val fftR = fft(xR)

        // --- 밴드별 에너지 계산 ---
        val results = mutableListOf<BandEnergy>()
        for (f in bands) {
            val bin = (f / (sampleRate / n.toDouble())).roundToInt().coerceIn(0, n - 1)
            val reL = fftL[bin].real
            val imL = fftL[bin].imag
            val reR = fftR[bin].real
            val imR = fftR[bin].imag

            val magL2 = reL * reL + imL * imL
            val magR2 = reR * reR + imR * imR
            val energy = ((magL2 + magR2) * 0.5).toLong()
            val rms = sqrt(energy.toDouble() / n).toFloat()
            val phase = atan2(imR, reR) - atan2(imL, reL)

            results += BandEnergy(f, energy, rms, phase)
        }

        return results
    }

    // ------------------------------------------------------------
    // 내부 FFT 구현
    // ------------------------------------------------------------
    private fun fft(x: DoubleArray): Array<Complex> {
        val N = x.size
        if (N <= 1) return arrayOf(Complex(x[0], 0.0))
        val even = fft(x.filterIndexed { i, _ -> i % 2 == 0 }.toDoubleArray())
        val odd = fft(x.filterIndexed { i, _ -> i % 2 == 1 }.toDoubleArray())
        val result = Array(N) { Complex(0.0, 0.0) }
        for (k in 0 until N / 2) {
            val t = Complex.polar(1.0, -2 * Math.PI * k / N) * odd[k]
            result[k] = even[k] + t
            result[k + N / 2] = even[k] - t
        }
        return result
    }

    private data class Complex(val real: Double, val imag: Double) {
        operator fun plus(o: Complex) = Complex(real + o.real, imag + o.imag)
        operator fun minus(o: Complex) = Complex(real - o.real, imag - o.imag)
        operator fun times(o: Complex) =
            Complex(real * o.real - imag * o.imag, real * o.imag + imag * o.real)

        companion object {
            fun polar(r: Double, theta: Double) = Complex(r * cos(theta), r * sin(theta))
        }
    }
}

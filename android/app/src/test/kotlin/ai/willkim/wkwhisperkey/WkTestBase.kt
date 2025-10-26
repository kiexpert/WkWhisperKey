package ai.willkim.wkwhisperkey

import kotlin.system.measureTimeMillis
import kotlin.math.*
import org.junit.jupiter.api.Assertions.fail

/**
 * ⚙️ WkTestBase
 * ------------------------------------------------------------
 * 윌김 테스트 환경의 표준 베이스 클래스.
 *  - 모든 stdout → stderr 자동 리디렉트
 *  - print/println/printf 표준화
 *  - assertTrue/False 확장 (콜스택 및 조건식 디버그 포함)
 *  - 실행시간 측정 measure()
 *  - FFT, 랜덤 유틸 등 공용 함수 포함
 */
open class WkTestBase {

    init {
        // ✅ 모든 System.out 출력을 stderr로 리디렉트
        System.setOut(System.err)
    }

    // ------------------------------------------------------------
    // 🔹 출력 도우미
    fun print(msg: Any?) {
        System.err.print(msg)
        System.err.flush()
    }

    fun println(msg: Any? = "") {
        System.err.println(msg)
        System.err.flush()
    }

    fun printf(format: String, vararg args: Any?) {
        System.err.printf(format + "\n", *args)
        System.err.flush()
    }

    // ------------------------------------------------------------
    // 🔹 타이밍 측정
    inline fun <T> measure(label: String, block: () -> T): T {
        lateinit var result: T
        val elapsed = measureTimeMillis {
            result = block()
        }
        println("⏱ [$label] took ${elapsed}ms")
        return result
    }

    // ------------------------------------------------------------
    // 🔹 확장 assert: 실패시 콜스택 전체 stderr 출력
    fun assertTrue(condition: Boolean, message: String = "") {
        if (!condition) {
            val stack = Throwable().stackTrace.drop(1)
                .joinToString("\n  at ") { it.toString() }
            System.err.println("❌ assertTrue failed: $message")
            System.err.println("  Stack:\n  at $stack\n")
            fail<String>("Assertion failed: $message")
        }
    }

    fun assertFalse(condition: Boolean, message: String = "") {
        if (condition) {
            val stack = Throwable().stackTrace.drop(1)
                .joinToString("\n  at ") { it.toString() }
            System.err.println("❌ assertFalse failed: $message")
            System.err.println("  Stack:\n  at $stack\n")
            fail<String>("Assertion failed: $message")
        }
    }

    // ------------------------------------------------------------
    // 🔹 간단 FFT 유틸 (디버그용)
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

    // ------------------------------------------------------------
    // 🔹 랜덤 샘플 유틸
    fun randomSignal(n: Int, amplitude: Int = 16000): IntArray =
        IntArray(n) { (sin(2 * Math.PI * 440 * it / 44100) * amplitude).toInt() }
}

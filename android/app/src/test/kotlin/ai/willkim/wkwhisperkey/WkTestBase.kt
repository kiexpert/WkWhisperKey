package ai.willkim.wkwhisperkey

import org.junit.jupiter.api.Assertions.fail
import kotlin.system.measureTimeMillis
import kotlin.math.*
import java.io.File

open class WkTestBase {

    init {
        // ✅ stdout → stderr 통합
        System.setOut(System.err)
    }

    // ------------------------------------------------------------
    // 🔹 공용 출력
    fun print(msg: Any?) = System.err.print(msg)
    fun println(msg: Any? = "") = System.err.println(msg)
    fun printf(fmt: String, vararg args: Any?) = System.err.printf(fmt + "\n", *args)

    // ------------------------------------------------------------
    // 🔹 소스라인 복원 헬퍼
    private fun findSourceExpression(trace: StackTraceElement): String {
        return try {
            val path = "android/app/src/test/kotlin/ai/willkim/wkwhisperkey/" + trace.fileName
            val file = File(path)
            if (!file.exists()) return "(source not found)"
            val line = file.readLines()[trace.lineNumber - 1].trim()
            line.takeIf { it.isNotEmpty() } ?: "(empty line)"
        } catch (e: Exception) {
            "(unknown condition)"
        }
    }

    // ------------------------------------------------------------
    // 🔹 assertTrue/False (디버그 확장)
    fun assertTrue(condition: Boolean, message: String = "") {
        if (!condition) {
            val trace = Throwable().stackTrace.first { it.className.contains("Test") }
            val srcLine = findSourceExpression(trace)
            System.err.println("❌ assertTrue failed at ${trace.fileName}:${trace.lineNumber}")
            System.err.println("  ↳ condition: $srcLine")
            if (message.isNotEmpty()) System.err.println("  ↳ message: $message")
            fail<String>("Assertion failed at ${trace.fileName}:${trace.lineNumber}")
        }
    }

    fun assertFalse(condition: Boolean, message: String = "") {
        if (condition) {
            val trace = Throwable().stackTrace.first { it.className.contains("Test") }
            val srcLine = findSourceExpression(trace)
            System.err.println("❌ assertFalse failed at ${trace.fileName}:${trace.lineNumber}")
            System.err.println("  ↳ condition: $srcLine")
            if (message.isNotEmpty()) System.err.println("  ↳ message: $message")
            fail<String>("Assertion failed at ${trace.fileName}:${trace.lineNumber}")
        }
    }

    // ------------------------------------------------------------
    // 🔹 실행 시간 측정
    inline fun <T> measure(label: String, block: () -> T): T {
        var r: T? = null
        val t = measureTimeMillis { r = block() }
        println("⏱ [$label] took ${t}ms")
        return r as T
    }

    // ------------------------------------------------------------
    // 🔹 간단 FFT
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
    // 🔹 랜덤 신호 생성
    fun randomSignal(n: Int, amplitude: Int = 16000): IntArray =
        IntArray(n) { (sin(2 * Math.PI * 440 * it / 44100) * amplitude).toInt() }
}

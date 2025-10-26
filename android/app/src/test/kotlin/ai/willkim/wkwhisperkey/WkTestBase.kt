package ai.willkim.wkwhisperkey

import kotlin.system.measureTimeMillis

/**
 * ✅ WkTestBase
 * 모든 Wk 테스트의 공통 기반 클래스.
 *  - print/println 리다이렉트(stderr)
 *  - 실행 시간 측정 헬퍼
 *  - 공용 FFT / 랜덤 데이터 유틸 포함 가능
 */
open class WkTestBase {

    // 표준 에러 출력 전용 print 함수
    fun print(msg: Any?) {
        System.err.print(msg)
        System.err.flush()
    }

    fun println(msg: Any? = "") {
        System.err.println(msg)
        System.err.flush()
    }

    fun printf(format: String, vararg args: Any?) {
        System.err.printf((format + "\n"), *args)
        System.err.flush()
    }

    /** 실행시간 측정 헬퍼 */
    inline fun <T> measure(label: String, block: () -> T): T {
        var result: T
        val elapsed = measureTimeMillis {
            result = block()
        }
        println("⏱ $label took ${elapsed}ms")
        return result
    }
}

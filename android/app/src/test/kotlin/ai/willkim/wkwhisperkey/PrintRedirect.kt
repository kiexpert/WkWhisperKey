package ai.willkim.wkwhisperkey

/**
 * ✅ 표준 출력 리다이렉트 유틸
 * 모든 print/println/printf 호출을 stderr로 강제 연결.
 * Gradle 테스트 로그에서 즉시 보이게 함.
 */

@Suppress("NOTHING_TO_INLINE")
inline fun print(msg: Any?) {
    System.err.print(msg)
    System.err.flush()
}

@Suppress("NOTHING_TO_INLINE")
inline fun println(msg: Any? = "") {
    System.err.println(msg)
    System.err.flush()
}

@Suppress("NOTHING_TO_INLINE")
inline fun printf(format: String, vararg args: Any?) {
    System.err.printf((format + "\n"), *args)
    System.err.flush()
}

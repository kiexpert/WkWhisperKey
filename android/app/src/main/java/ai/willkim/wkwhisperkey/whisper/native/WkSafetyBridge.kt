package ai.willkim.wkwhisperkey.whisper.native

import android.content.Context

/**
 * 네이티브 코드가 Toast/알림을 띄울 수 있도록
 * ApplicationContext를 JNI에 등록하는 브리지.
 */
object WkSafetyBridge {
    init {
        try {
            System.loadLibrary("whisper")
        } catch (_: UnsatisfiedLinkError) { }
    }

    external fun registerContext(context: Context)
}

package ai.willkim.wkwhisperkey.core

import android.content.Context

class WkWhisperEngine(context: Context) {
    var onResult: ((String, Float) -> Unit)? = null

    init {
        System.loadLibrary("wkwhispercore")
        nativeInit(context.assets)
    }

    fun feed(buffer: ShortArray) {
        val text = nativeInfer(buffer)
        if (text.isNotEmpty()) onResult?.invoke(text, 0.97f)
    }

    fun release() = nativeRelease()

    private external fun nativeInit(assetMgr: Any)
    private external fun nativeInfer(samples: ShortArray): String
    private external fun nativeRelease()
}

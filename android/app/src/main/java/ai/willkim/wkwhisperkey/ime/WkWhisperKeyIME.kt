package ai.willkim.wkwhisperkey.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import kotlinx.coroutines.*
import ai.willkim.wkwhisperkey.R        // ✅ 추가
import ai.willkim.wkwhisperkey.audio.WkAudioCapture
import ai.willkim.wkwhisperkey.core.WkWhisperEngine

class WkWhisperKeyIME : InputMethodService() {
    private lateinit var engine: WkWhisperEngine
    private lateinit var capture: WkAudioCapture
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        engine = WkWhisperEngine(this)
        capture = WkAudioCapture(this) { buffer ->
            scope.launch { engine.feed(buffer) }
        }
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.wk_keyboard, null)
        val info = view.findViewById<TextView>(R.id.statusText)
        engine.onResult = { text, conf ->
            info.text = "$text  (${(conf * 100).toInt()}%)"
            currentInputConnection.commitText(text, 1)
        }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        capture.start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        capture.stop()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        capture.release()
        engine.release()
        super.onDestroy()
    }
}

package ai.willkim.wkwhisperkey.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import ai.willkim.wkwhisperkey.ui.CandidateView
import ai.willkim.wkwhisperkey.engine.WhisperEngine
import ai.willkim.wkwhisperkey.data.WhisperStore
import ai.willkim.wkwhisperkey.worker.FineTuneWorker

/**
 * WhisperIME.kt
 * - 메인 입력 서비스 (속삭임 기반 입력기)
 * - WhisperEngine, WhisperStore, FineTuneWorker 연동
 */

class WhisperIME : InputMethodService() {

    private lateinit var candidateView: CandidateView
    private val whisperEngine = WhisperEngine()
    private var currentWave: ByteArray? = null
    private var recognizedSentence = ""

    override fun onCreateInputView(): View {
        candidateView = CandidateView(this).apply {
            onSentenceClicked = { commitRecognizedText(false) }
            onEnterLongPressed = { commitRecognizedText(true) }
        }

        whisperEngine.onResult = { text, wave ->
            recognizedSentence = text
            currentWave = wave
            candidateView.showRecognized(text)
        }

        return candidateView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        candidateView.showHint("속삭이면 입력됩니다.")
        whisperEngine.startCapture()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        val edited = currentInputConnection.getExtractedText(null, 0)?.text.toString()
        currentWave?.let { wave ->
            WhisperStore.insert(applicationContext, wave, edited)
        }
        FineTuneWorker.enqueue(applicationContext)
        whisperEngine.stopCapture()
        super.onFinishInputView(finishingInput)
    }

    private fun commitRecognizedText(sendEnter: Boolean) {
        val ic = currentInputConnection ?: return
        ic.commitText(recognizedSentence, 1)
        if (sendEnter) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
    }
}

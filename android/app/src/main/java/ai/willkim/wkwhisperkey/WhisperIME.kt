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
        currentWave?.let { WhisperStore.insert(it, edited) }
        FineTuneWorker.enqueue(applicationContext)
        whisperEngine.stopCapture()
        super.onFinishInputView(finishingInput)
    }

    private fun commitRecognizedText(sendEnter: Boolean) {
        val ic = currentInputConnection
        ic.commitText(recognizedSentence, 1)
        if (sendEnter) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    }
}

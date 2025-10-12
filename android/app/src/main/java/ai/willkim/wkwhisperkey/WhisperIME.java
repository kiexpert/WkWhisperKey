package ai.willkim.wkwhisperkey;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class WhisperIME extends InputMethodService {

    private CandidateView candidateView;
    private final WhisperEngine whisperEngine = new WhisperEngine();
    private byte[] currentWave;
    private String recognizedSentence = "";

    @Override
    public View onCreateInputView() {
        candidateView = new CandidateView(this);
        candidateView.setOnSentenceClicked(() -> commitRecognizedText(false));
        candidateView.setOnEnterLongPressed(() -> commitRecognizedText(true));

        whisperEngine.setOnResult((text, wave) -> {
            recognizedSentence = text;
            currentWave = wave;
            candidateView.showRecognized(text);
        });

        return candidateView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        candidateView.showHint("속삭이면 입력됩니다.");
        whisperEngine.startCapture();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && currentWave != null) {
            CharSequence edited = ic.getExtractedText(null, 0).text;
            WhisperStore.insert(currentWave, edited.toString());
        }
        FineTuneWorker.enqueue(getApplicationContext());
        whisperEngine.stopCapture();
        super.onFinishInputView(finishingInput);
    }

    private void commitRecognizedText(boolean sendEnter) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(recognizedSentence, 1);
        if (sendEnter)
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }
}

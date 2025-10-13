package ai.willkim.wkwhisperkey.engine

class WhisperEngine {
    var onResult: ((String, ByteArray) -> Unit)? = null
    private var capturing = false

    fun startCapture() {
        capturing = true
        Thread {
            // 예시용 루프: 실제론 오디오 버퍼 캡처 → STT 모델 추론
            val fakeWave = ByteArray(1024)
            while (capturing) {
                Thread.sleep(2000)
                val result = "내일은 맑겠습니다"
                onResult?.invoke(result, fakeWave)
            }
        }.start()
    }

    fun stopCapture() { capturing = false }

    fun finetune(pairs: List<Pair<ByteArray, String>>) {
        // on-device 미세학습 (pseudo)
    }
}

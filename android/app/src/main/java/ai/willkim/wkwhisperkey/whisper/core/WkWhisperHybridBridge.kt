package ai.willkim.wkwhisperkey.whisper.core

import ai.willkim.wkwhisperkey.whisper.WhisperEngineInterface
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * WkWhisperHybridBridge v1.0
 * --------------------------
 * Whisper JNI + Local + API 세 엔진을 동시에 실행해 결과를 비교/합의.
 */
class WkWhisperHybridBridge(
    private val engineCpp: WhisperEngineInterface,
    private val engineLocal: WhisperEngineInterface,
    private val engineApi: WhisperEngineInterface,
    private val onFinalResult: (text: String, details: ConsensusDetail) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var running = true

    /** PCM 입력 → 세 엔진에 병렬 전송 */
    fun feedPcm(pcm: ByteBuffer) {
        if (!running) return
        scope.launch {
            try {
                val results = asyncAll(pcm)
                val consensus = compareResults(results)
                onFinalResult(consensus.text, consensus)
            } catch (e: Exception) {
                Log.e("WhisperHybrid", "feed error: ${e.message}")
            }
        }
    }

    private suspend fun asyncAll(pcm: ByteBuffer): List<Pair<String, String>> = coroutineScope {
        val cpp = async { "JNI" to (engineCpp.transcribe(pcm.duplicate()) ?: "") }
        val local = async { "Local" to (engineLocal.transcribe(pcm.duplicate()) ?: "") }
        val api = async { "API" to (engineApi.transcribe(pcm.duplicate()) ?: "") }
        listOf(cpp.await(), local.await(), api.await())
    }

    private fun compareResults(results: List<Pair<String, String>>): ConsensusDetail {
        val map = results.toMap()
        val cpp = map["JNI"].orEmpty()
        val local = map["Local"].orEmpty()
        val api = map["API"].orEmpty()

        val texts = listOf(cpp, local, api).filter { it.isNotBlank() }
        val most = texts.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        val agreeCount = texts.count { it == most }
        val confidence = agreeCount / 3f

        Log.i("WhisperHybrid", "JNI='$cpp' / Local='$local' / API='$api' → chosen='$most' (conf=$confidence)")
        return ConsensusDetail(cpp, local, api, most, confidence)
    }

    fun stop() {
        running = false
        scope.cancel()
    }
}

/** 세 엔진 결과 비교용 데이터 구조 */
data class ConsensusDetail(
    val cpp: String,
    val local: String,
    val api: String,
    val text: String,
    val confidence: Float
)

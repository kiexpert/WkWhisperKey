package ai.willkim.wkwhisperkey.whisper.core

import android.util.Log
import java.io.File

/**
 * WkWhisperConsensusLog
 * ---------------------
 * WhisperHybrid 결과 로그를 파일로 저장하여 학습용으로 사용.
 * /sdcard/WkWhisperLogs/log_timestamp.txt
 */
object WkWhisperConsensusLog {
    private val dir = File("/sdcard/WkWhisperLogs").apply { mkdirs() }

    fun save(detail: ConsensusDetail) {
        val log = """
            JNI=${detail.cpp}
            Local=${detail.local}
            API=${detail.api}
            Final=${detail.text}
            Confidence=${detail.confidence}
        """.trimIndent()
        val f = File(dir, "log_${System.currentTimeMillis()}.txt")
        f.writeText(log)
        Log.d("WhisperLog", "Saved: ${f.name}")
    }
}

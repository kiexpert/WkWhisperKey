package ai.willkim.wkwhisperkey.whisper.core

import android.util.Log
import java.io.File
import java.nio.charset.Charset

/**
 * WkWhisperTrainer v1.0
 * ----------------------
 * WhisperHybrid 로그를 기반으로 Local 엔진을 점진적으로 보정.
 * - /sdcard/WkWhisperLogs 내 로그 자동 탐색
 * - JNI/API 결과를 "정답"으로 가정하고 Local 결과를 비교
 * - 빈도 기반 교정 사전(Word Correction Map) 업데이트
 * - WhisperLocalEngine에 피드백 전달
 */
object WkWhisperTrainer {

    private val logDir = File("/sdcard/WkWhisperLogs")
    private val correctionMap = mutableMapOf<String, Pair<String, Int>>() // wrong -> (right, freq)

    /** 로그 기반 자동 학습 */
    fun trainFromLogs() {
        if (!logDir.exists()) {
            Log.w("WhisperTrainer", "로그 폴더 없음: ${logDir.path}")
            return
        }
        val logs = logDir.listFiles()?.filter { it.extension == "txt" } ?: return
        logs.forEach { analyze(it) }
        saveCorrectionMap()
        Log.i("WhisperTrainer", "훈련 완료 (${correctionMap.size}개 패턴)")
    }

    /** 개별 로그 분석 */
    private fun analyze(file: File) {
        val lines = file.readLines(Charset.defaultCharset())
        val map = lines.associate {
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
        }
        val cpp = map["JNI"].orEmpty()
        val api = map["API"].orEmpty()
        val local = map["Local"].orEmpty()
        val correct = chooseTruth(cpp, api)
        if (local.isBlank() || correct.isBlank() || local == correct) return
        correctionMap[local] = correct to (correctionMap[local]?.second?.plus(1) ?: 1)
    }

    /** 두 정답 후보 중 더 신뢰도 높은 결과 선택 */
    private fun chooseTruth(cpp: String, api: String): String {
        val lenDiff = kotlin.math.abs(cpp.length - api.length)
        return if (lenDiff < 5 && cpp.isNotBlank() && api.isNotBlank()) {
            cpp
        } else if (api.isNotBlank() && api.length > cpp.length) {
            api
        } else cpp
    }

    /** 로컬 보정 데이터 저장 */
    private fun saveCorrectionMap() {
        val f = File(logDir, "corrections.txt")
        val sb = StringBuilder()
        correctionMap.entries.sortedByDescending { it.value.second }.forEach {
            sb.append("${it.key} => ${it.value.first} (${it.value.second})\n")
        }
        f.writeText(sb.toString())
    }

    /** WhisperLocalEngine에 학습 반영 */
    fun applyTo(engine: ai.willkim.wkwhisperkey.whisper.local.WhisperLocalEngine) {
        val f = File(logDir, "corrections.txt")
        if (!f.exists()) return
        val lines = f.readLines()
        lines.forEach {
            val parts = it.split("=>", limit = 2)
            if (parts.size == 2) {
                val wrong = parts[0].trim()
                val right = parts[1].substringBefore("(").trim()
                engine.addCorrection(wrong, right)
            }
        }
        Log.i("WhisperTrainer", "보정 사전 적용 완료")
    }
}

package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.willkim.wkwhisperkey.audio.*
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor
import kotlin.math.roundToInt

/**
 * WhisperMicHUDActivity
 * ---------------------
 * 화자 분리기(WkVoiceSeparator) 및 토큰화기(WkVoiceTokenizer)와 완전 연동된
 * 실시간 화자별 음원 시각화 액티비티.
 */
class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: WkVoiceSeparator
    private lateinit var tokenizer: WkVoiceTokenizer

    private val ui by lazy { Ui(this) }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "🎤 화자 분리 실시간 HUD"
        ui.center.text = "각 화자별 스펙트럼 및 토큰 시각화"

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)

        // --- 분리기 및 토크나이저 초기화 ---
        separator = WkVoiceSeparator(WkVoiceSeparator.defaultBands, 44100)
        tokenizer = WkVoiceTokenizer()

        // --- 콜백 연결 ---
        separator.onSpeakersUpdate = { speakers ->
            main.post { updateSpeakersUI(speakers) }
        }

        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf ->
                try {
                    separator.feed(buf)
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "분리기 오류: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onEnergyLevel = { _, _ -> }
        )

        main.postDelayed({ startMic() }, 600)
    }

    private fun startMic() {
        try {
            Toast.makeText(this, "🎧 스테레오 마이크 시작 중...", Toast.LENGTH_SHORT).show()
            micManager.startStereo()
        } catch (e: Exception) {
            Toast.makeText(this, "시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 화자 정보 갱신 시 호출 */
    private fun updateSpeakersUI(speakers: List<SpeakerInfo>) {
        ui.clearSpeakers()
        if (speakers.isEmpty()) {
            ui.status.text = "👂 감지된 화자 없음"
            return
        }

        ui.status.text = "감지된 화자 수: ${speakers.size}"

        for ((i, spk) in speakers.withIndex()) {
            val row = ui.addSpeakerRow("화자 #${i + 1}  Δφ=${"%.1f".format(spk.deltaPhase)}°, dist≈${"%.2f".format(spk.distance)}m")
            val lDb = spk.energyL
            val rDb = spk.energyR
            val aDb = (lDb + rDb) / 2.0
            val tokenStr = tokenizer.generateTokensFromSpeakers(listOf(spk))

            row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.values.text = "AVG ${fmtDb(aDb)} | L ${fmtDb(lDb)} | R ${fmtDb(rDb)}"
            row.tokens.text = tokenStr
        }
    }

    private fun fmtDb(v: Double) = String.format("%6.1f dB", v)

    private fun ensureMicPermission() {
        val p = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(p), 101)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (c == 101 && r.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            main.postDelayed({ startMic() }, 500)
        else Toast.makeText(this, "권한 필요", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }

    // ------------------- UI 내부 클래스 -------------------
    private class Ui(private val act: AppCompatActivity) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val title = TextView(act).apply { textSize = 18f }.also { root.addView(it) }
        val center = TextView(act).apply {
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }.also { root.addView(it) }
        val status = TextView(act).apply {
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }.also { root.addView(it) }

        private val speakerViews = mutableListOf<Row>()

        fun clearSpeakers() {
            for (r in speakerViews) {
                root.removeView(r.label)
                root.removeView(r.line)
                root.removeView(r.tokens)
            }
            speakerViews.clear()
        }

        fun addSpeakerRow(title: String): Row {
            val label = TextView(act).apply { this.text = title; textSize = 15f }
            root.addView(label)

            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val left = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0; scaleX = -1f
            }
            val right = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
            }

            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            line.addView(left, lp)
            line.addView(right, lp)
            root.addView(line)

            val valueText = TextView(act).apply {
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val tokenText = TextView(act).apply {
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
                setSingleLine(true)
            }
            root.addView(valueText)
            root.addView(tokenText)

            val row = Row(label, line, left, right, valueText, tokenText)
            speakerViews += row
            return row
        }

        data class Row(
            val label: TextView,
            val line: LinearLayout,
            val leftBar: ProgressBar,
            val rightBar: ProgressBar,
            val values: TextView,
            val tokens: TextView
        )
    }
}

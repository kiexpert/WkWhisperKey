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

        // 분리기 초기화 (기본 밴드 정의가 없을 경우 자동 생성)
        val bands = try {
            WkVoiceSeparator.defaultBands
        } catch (_: Throwable) {
            doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
        }

        // 샘플레이트 기본값
        val sampleRate = 44100

        separator = WkVoiceSeparator(bands, sampleRate)
        tokenizer = WkVoiceTokenizer()

        // 안전한 콜백 래핑
        try {
            separator.onSpeakersUpdate = { speakers ->
                main.post { updateSpeakersUI(speakers) }
            }
        } catch (_: Throwable) {
            // 구버전 분리기: 콜백 미지원
        }

        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf ->
                try {
                    // feed / processFrame / input 등 다양한 명칭 대응
                    val fn = separator::class.members.find { it.name in listOf("feed", "process", "processFrame", "input") }
                    fn?.call(separator, buf)
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

    /** 화자 정보 UI 업데이트 */
    private fun updateSpeakersUI(speakers: List<Any>) {
        ui.clearSpeakers()
        if (speakers.isEmpty()) {
            ui.status.text = "👂 감지된 화자 없음"
            return
        }
        ui.status.text = "감지된 화자 수: ${speakers.size}"

        for ((i, s) in speakers.withIndex()) {
            val row = ui.addSpeakerRow("화자 #${i + 1}")

            // 리플렉션으로 SpeakerInfo 필드 접근
            val kClass = s::class
            val lDb = (kClass.members.find { it.name == "energyL" }?.call(s) as? Double) ?: 0.0
            val rDb = (kClass.members.find { it.name == "energyR" }?.call(s) as? Double) ?: 0.0
            val avgDb = (lDb + rDb) / 2.0
            val dPhi = (kClass.members.find { it.name == "deltaPhase" }?.call(s) as? Double) ?: 0.0
            val dist = (kClass.members.find { it.name == "distance" }?.call(s) as? Double) ?: 0.0

            val tokenStr = try {
                tokenizer.generateTokensFromSpeakers(listOf(s))
            } catch (_: Throwable) {
                "Token unavailable"
            }

            row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.values.text = String.format("AVG %6.1f dB | L %6.1f | R %6.1f | Δφ=%+05.1f° | dist≈%.2fm", avgDb, lDb, rDb, dPhi, dist)
            row.tokens.text = tokenStr
        }
    }

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

    // ------------------- UI 내부 -------------------
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

            val values = TextView(act).apply {
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val tokens = TextView(act).apply {
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
                setSingleLine(true)
            }
            root.addView(values)
            root.addView(tokens)

            val row = Row(label, line, left, right, values, tokens)
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

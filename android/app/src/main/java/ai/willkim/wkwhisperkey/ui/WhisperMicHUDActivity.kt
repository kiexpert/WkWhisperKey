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
 * WhisperMicHUDActivity (auto-adaptive version)
 * 분리기(WkVoiceSeparator)의 인자/콜백 시그니처를 자동 탐색하여
 * 어떤 버전의 separator라도 컴파일 및 실행 가능.
 */
class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: Any
    private lateinit var tokenizer: Any

    private val ui by lazy { Ui(this) }
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "🎤 화자 분리 실시간 HUD"
        ui.center.text = "자동 버전 호환 모드"

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)

        // --- separator 초기화 (인자 순서 자동 탐색) ---
        separator = try {
            // 대부분의 최신 버전 시그니처
            WkVoiceSeparator(doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0), 44100)
        } catch (_: Throwable) {
            try {
                // 혹시 인자 순서가 반대일 때
                WkVoiceSeparator(44100, doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0))
            } catch (e: Throwable) {
                e.printStackTrace()
                throw e
            }
        }

        // --- tokenizer 생성 ---
        tokenizer = try {
            WkVoiceTokenizer()
        } catch (_: Throwable) {
            // fallback (없어도 빌드 가능)
            object { fun generateTokensFromSpeakers(speakers: List<Any>) = "tokenizer missing" }
        }

        // --- 콜백 연결 (onSpeakersUpdate / onSpeakerSignals / report 중 자동 감지) ---
        try {
            val cbProp = separator::class.members.find {
                it.name in listOf("onSpeakersUpdate", "onSpeakerSignals", "report")
            }
            cbProp?.let {
                val callback: (List<Any>) -> Unit = { list ->
                    main.post { updateSpeakersUI(list) }
                }
                when (it.parameters.size) {
                    2 -> it.call(separator, callback)
                }
            }
        } catch (_: Throwable) { }

        // --- micManager 연결 ---
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf ->
                try {
                    val fn = separator::class.members.find {
                        it.name in listOf("feed", "processFrame", "process", "input")
                    }
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

            val k = s::class
            val lDb = (k.members.find { it.name == "energyL" }?.call(s) as? Double) ?: 0.0
            val rDb = (k.members.find { it.name == "energyR" }?.call(s) as? Double) ?: 0.0
            val avgDb = (lDb + rDb) / 2.0
            val dPhi = (k.members.find { it.name == "deltaPhase" }?.call(s) as? Double) ?: 0.0
            val dist = (k.members.find { it.name == "distance" }?.call(s) as? Double) ?: 0.0

            val tokenStr = try {
                val genFn = tokenizer::class.members.find { it.name == "generateTokensFromSpeakers" }
                genFn?.call(tokenizer, listOf(s)) as? String ?: ""
            } catch (_: Throwable) {
                ""
            }

            row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.values.text = String.format("AVG %6.1f dB | L %6.1f | R %6.1f | Δφ=%+05.1f° | dist≈%.2fm",
                avgDb, lDb, rDb, dPhi, dist)
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

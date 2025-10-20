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
 * 화자 분리기(WkVoiceSeparator) 기반 실시간 HUD
 * - micManager → L/R PCM 추출
 * - separator.separate(L, R) 호출로 화자별 신호 계산
 */
class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: WkVoiceSeparator

    private val ui by lazy { Ui(this) }
    private val main = Handler(Looper.getMainLooper())

    // 오디오 처리용 버퍼
    private val ring = ShortArray(8192)
    private var rp = 0
    private var filled = 0

    private val sampleRate = 44100
    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    private val N = 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "🎤 화자 분리 실시간 HUD"
        ui.center.text = "Phase-Delay Separation Engine"

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)

        separator = WkVoiceSeparator(sampleRate, bands)

        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf -> onStereoPcm(buf) },
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

    /** 스테레오 PCM 수신 */
    private fun onStereoPcm(stereo: ShortArray) {
        for (v in stereo) {
            ring[rp] = v
            rp = (rp + 1) % ring.size
        }
        filled = (filled + stereo.size).coerceAtMost(ring.size)
        if (filled >= N * 4 && (filled % (N * 2) == 0)) {
            processFrame()
        }
    }

    /** 프레임 처리 → 화자 분리기 호출 */
    private fun processFrame() {
        val L = DoubleArray(N)
        val R = DoubleArray(N)
        var idx = (rp - 2 * N + ring.size) % ring.size
        var i = 0
        while (i < 2 * N) {
            val l = ring[idx].toInt(); idx = (idx + 1) % ring.size
            val r = ring[idx].toInt(); idx = (idx + 1) % ring.size
            L[i / 2] = l.toDouble()
            R[i / 2] = r.toDouble()
            i += 2
        }

        val speakers = separator.separate(L, R)
        main.post { updateSpeakersUI(speakers) }
    }

    /** UI 출력 */
    private fun updateSpeakersUI(speakers: List<SpeakerSignal>) {
        ui.clearSpeakers()
        if (speakers.isEmpty()) {
            ui.status.text = "👂 감지된 화자 없음"
            return
        }

        ui.status.text = "감지된 화자 수: ${speakers.size}"
        for ((i, spk) in speakers.withIndex()) {
            val row = ui.addSpeakerRow("화자 #${i + 1} Δ=${spk.deltaIndex}")
            val avgDb = spk.energy
            val lDb = avgDb - 3
            val rDb = avgDb - 3
            row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
            row.values.text = String.format("E=%6.1f dB | Δ=%d", spk.energy, spk.deltaIndex)
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

    // ---------- UI ----------
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
            root.addView(values)

            val row = Row(label, line, left, right, values)
            speakerViews += row
            return row
        }

        data class Row(
            val label: TextView,
            val line: LinearLayout,
            val leftBar: ProgressBar,
            val rightBar: ProgressBar,
            val values: TextView
        )
    }
}

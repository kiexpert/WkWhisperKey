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
import kotlin.math.*

/**
 * WhisperMicHUDActivity
 * 화자 분리 + 실시간 위상 시각화 + 토큰 뷰어 통합
 * (C) 2025 Will Kim
 */

class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: WkVoiceSeparator
    private val ui by lazy { Ui(this) }
    private val main = Handler(Looper.getMainLooper())

    // ---------- 분석 파라미터 ----------
    private val sampleRate = 44100
    private val frameMs = 20
    private val N = (sampleRate * frameMs / 1000.0).roundToInt()
    private val hop = N / 2
    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)
    private val ring = ShortArray(4 * N)
    private var rp = 0
    private var filled = 0
    private val win = DoubleArray(N) { i -> 0.5 - 0.5 * cos(2.0 * Math.PI * i / (N - 1)) }

    private lateinit var noiseFloor: DoubleArray
    private val tokenizer = WkVoiceTokenizer()
    private val rows = mutableListOf<Row>()

    private data class Row(
        val label: TextView,
        val leftBar: ProgressBar,
        val rightBar: ProgressBar,
        val values: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "화자 분리 + 위상 스펙트럼 + 토큰 뷰어"
        ui.center.text = "← 화자 왼쪽 | 중앙 | 화자 오른쪽 →"

        for (i in bands.indices)
            rows += ui.addBandRow("f${i} = ${bands[i].toInt()} Hz")

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)
        noiseFloor = DoubleArray(bands.size) { 1e-9 }

        separator = WkVoiceSeparator(sampleRate, bands)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf -> onPcm(buf) },
            onEnergyLevel = { _, _ -> }
        )

        main.postDelayed({ startMic() }, 600)
    }

    private fun startMic() {
        try {
            Toast.makeText(this, "🎤 스테레오 마이크 시작 중...", Toast.LENGTH_SHORT).show()
            micManager.startStereo()
        } catch (e: Exception) {
            Toast.makeText(this, "시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPcm(stereo: ShortArray) {
        for (i in stereo.indices) {
            ring[rp] = stereo[i]
            rp = (rp + 1) % ring.size
        }
        filled = (filled + stereo.size).coerceAtMost(ring.size)
        if (filled >= 2 * N && (filled % (2 * hop) == 0)) {
            processFrame()
        }
    }

    private fun processFrame() {
        val L = DoubleArray(N)
        val R = DoubleArray(N)
        var idx = (rp - 2 * N + ring.size) % ring.size
        var j = 0
        while (j < 2 * N) {
            val l = ring[idx].toInt()
            idx = (idx + 1) % ring.size
            val r = ring[idx].toInt()
            idx = (idx + 1) % ring.size
            L[j / 2] = l.toDouble()
            R[j / 2] = r.toDouble()
            j += 2
        }

        // 윈도우 적용
        for (i in 0 until N) {
            val w = win[i]
            L[i] *= w
            R[i] *= w
        }

        val speakers = try {
            separator.separate(L, R)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        main.post {
            // --- 시각화 / 노이즈 처리 ---
            val avgL = L.map { abs(it) }.average()
            val avgR = R.map { abs(it) }.average()
            val avgA = (avgL + avgR) / 2.0
            val phaseArr = DoubleArray(bands.size) { 0.0 }
            val magArr = DoubleArray(bands.size) { avgA }

            ui.phaseGraph.onFrame(phaseArr, magArr)

            // --- 토큰 생성 ---
            val tokens = tokenizer.generateTokensFromSpeakers(speakers)
            ui.tokenText.text = tokens.joinToString(" ")
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

    // ---------- 내부 UI ----------
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

        val phaseGraph = WkPhaseScatterView(act).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300
            )
        }.also { root.addView(it) }

        val tokenText = TextView(act).apply {
            textSize = 13f
            gravity = Gravity.START
            setHorizontallyScrolling(true)
            text = "화자별 토큰 출력 대기 중..."
        }.also { root.addView(it) }

        fun addBandRow(text: String): Row {
            val label = TextView(act).apply { this.text = text; textSize = 15f }
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
                this.text =
                    "AVG  0.0 dB | L  0.0 dB, φ +000° | R  0.0 dB, φ +000° | Δφ +000°"
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(valueText)

            return Row(label, left, right, valueText)
        }
    }
}

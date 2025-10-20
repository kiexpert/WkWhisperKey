package ai.willkim.wkwhisperkey.ui

import ai.willkim.wkwhisperkey.audio.*
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.*

class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: WkVoiceSeparator
    private val ui by lazy { Ui(this) }
    private val main = Handler(Looper.getMainLooper())

    private val sampleRate = 44100
    private val frameMs = 20
    private val N = (sampleRate * frameMs / 1000.0).roundToInt()
    private val hop = N / 2
    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)

    private val ring = ShortArray(4 * N)
    private var rp = 0
    private var filled = 0
    private var frameCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "🎤 화자 분리 실시간 HUD"
        ui.center.text = "Phase-Delay Separation Engine"

        WkSafetyMonitor.initialize(this)
        ensureMicPermission()

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
            Toast.makeText(this, "🎙 스테레오 수집 시작", Toast.LENGTH_SHORT).show()
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
        if (filled >= 2 * N && (frameCount++ % 2 == 0)) processFrame()
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

        val speakers = separator.separate(L, R)
        updateUi(speakers)
    }

    private fun updateUi(speakers: List<SpeakerSignal>) {
        main.post {
            val displaySpeakers = speakers.sortedByDescending { it.energy }.take(7)
            val sb = StringBuilder()
            sb.append("감지된 화자 수: ${displaySpeakers.size}\n\n")
            displaySpeakers.forEachIndexed { i, spk ->
                sb.append(String.format(
                    "#%-2d  E=%6.1f dB | Δ=%+4d | samples=%d\n",
                    i + 1, spk.energy, spk.deltaIndex, spk.samples.size
                ))
            }
            ui.logText.text = sb.toString()
            ui.logScroll.post { ui.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
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

    private class Ui(act: AppCompatActivity) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(act).apply {
            textSize = 18f
            gravity = Gravity.START
        }.also { root.addView(it) }

        val center = TextView(act).apply {
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 16)
        }.also { root.addView(it) }

        // 스크롤 가능한 로그 영역
        val logScroll: ScrollView
        val logText: TextView

        init {
            logText = TextView(act).apply {
                textSize = 12f
                gravity = Gravity.START
                setTextColor(Color.DKGRAY)
                setPadding(4, 12, 4, 4)
            }
            logScroll = ScrollView(act).apply {
                isFillViewport = true
                addView(logText)
            }
            root.addView(logScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ))
        }
    }
}

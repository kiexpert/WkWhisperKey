package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.willkim.wkwhisperkey.audio.*
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor
import kotlin.math.*

class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private lateinit var separator: WkVoiceSeparator
    private val main = Handler(Looper.getMainLooper())

    // ----- 분석 파라미터 -----
    private val sampleRate = 44100
    private val frameMs = 20
    private val N = (sampleRate * frameMs / 1000.0).roundToInt()
    private val hop = N / 2
    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)

    private val ring = ShortArray(4 * N)
    private var rp = 0
    private var filled = 0

    private val infoText by lazy { TextView(this) }
    private val speakerMap by lazy { WkSpeakerMapView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "🎤 멀티발성키 화자분리 뷰"
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        root.addView(speakerMap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 500
        ))

        infoText.textSize = 13f
        infoText.setTextColor(Color.WHITE)
        root.addView(infoText)
        setContentView(root)

        WkSafetyMonitor.initialize(this)
        separator = WkVoiceSeparator(sampleRate, bands)
        ensureMicPermission()

        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf -> onPcm(buf) },
            onEnergyLevel = { _, _ -> }
        )
        main.postDelayed({ startMic() }, 600)
    }

    private fun startMic() {
        Toast.makeText(this, "🎧 마이크 활성화 중...", Toast.LENGTH_SHORT).show()
        micManager.startStereo()
    }

    private fun onPcm(stereo: ShortArray) {
        for (i in stereo.indices) {
            ring[rp] = stereo[i]
            rp = (rp + 1) % ring.size
        }
        filled = (filled + stereo.size).coerceAtMost(ring.size)
        if (filled >= 2 * N && (filled % (2 * hop) == 0)) processFrame()
    }

    private fun processFrame() {
        val L = DoubleArray(N)
        val R = DoubleArray(N)
        var idx = (rp - 2 * N + ring.size) % ring.size
        var j = 0
        while (j < 2 * N) {
            val l = ring[idx].toInt(); idx = (idx + 1) % ring.size
            val r = ring[idx].toInt(); idx = (idx + 1) % ring.size
            L[j / 2] = l.toDouble()
            R[j / 2] = r.toDouble()
            j += 2
        }

        try {
            val speakers = separator.separate(L, R)
            val sorted = speakers.sortedByDescending { it.energy }.take(7)

            val sb = StringBuilder()
            sb.append("감지된 화자 수: ${speakers.size}\n")
            for ((i, s) in sorted.withIndex()) {
                sb.append(
                    String.format(
                        "#%-2d  E=%5.1f dB | Δ=%+4d | d=%.2fm\n",
                        i + 1, s.energy, s.deltaIndex, s.distance
                    )
                )
            }
            infoText.text = sb.toString()

            // 화자 및 발성키 모두 지도에 전달
            speakerMap.updateSpeakers(sorted, separator.activeKeys)

        } catch (e: Exception) {
            infoText.text = "분석 오류: ${e.message}"
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
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }
}

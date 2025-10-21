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
            LinearLayout.LayoutParams.MATCH_PARENT, 400
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

        // 화자 분리 수행
        try {
            val speakers = separator.separate(L, R)
            val sorted = speakers.sortedByDescending { it.energy }.take(7)
            val sb = StringBuilder()
            sb.append("화자수 ${sorted.size}\n")
            sb.append("ID | Δindex | 거리(m) | 에너지(dB)\n")
            for (s in sorted) {
                sb.append(String.format("%4d | %7d | %7.2f | %7.1f\n",
                    s.id, s.deltaIndex, s.distance, s.energy))
            }
            infoText.text = sb.toString()
            speakerMap.updateSpeakers(sorted)
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

/**
 * WkSpeakerMapView
 * └─ 화자 거리/각도 기반 실시간 맵
 */
class WkSpeakerMapView(ctx: android.content.Context) : View(ctx) {
    private val paint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 4f
        isAntiAlias = true
        textSize = 24f
    }
    private var speakers: List<SpeakerSignal> = emptyList()

    fun updateSpeakers(list: List<SpeakerSignal>) {
        speakers = list
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val top = 80f
        val maxDist = (speakers.maxOfOrNull { it.distance } ?: 1.0).toFloat()

        // 마이크 표시
        paint.color = Color.LTGRAY
        c.drawCircle(cx - 100, top, 10f, paint)
        c.drawCircle(cx + 100, top, 10f, paint)
        c.drawText("L", cx - 130, top + 10f, paint)
        c.drawText("R", cx + 115, top + 10f, paint)

        // 화자 표시
        for ((i, spk) in speakers.withIndex()) {
            val rel = spk.deltaIndex / 200.0f  // 좌우 위치 추정 (단순 스케일)
            val distRatio = (spk.distance / maxDist).toFloat().coerceIn(0f, 1f)
            val x = cx + rel * 200f
            val y = top + 50f + distRatio * (h - 100f)
            val hue = (i * 45f) % 360f
            paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            c.drawCircle(x, y, 16f, paint)
            c.drawText("%.1fm".format(spk.distance), x - 35f, y + 30f, paint)
        }
    }
}

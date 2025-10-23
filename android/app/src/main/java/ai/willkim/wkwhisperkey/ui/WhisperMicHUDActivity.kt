package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.Context
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
    private val separator = WkVoiceSeparatorShard.instance
    private val main = Handler(Looper.getMainLooper())

    private val sampleRate = 44100
    private val frameMs = 20
    private val N = (sampleRate * frameMs / 1000.0).roundToInt()
    private val hop = N / 2
    private val PAD_SAMPLES = 600

    // 🔹 패딩 포함 안전 버퍼
    private val ring = ShortArray(4 * N + 2 * PAD_SAMPLES)
    private var rp = 0
    private var filled = 0

    private val infoText by lazy { TextView(this) }
    private val speakerMap by lazy { WkSpeakerMapView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- FrameLayout 루트 ----------
        val root = FrameLayout(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(this).apply {
            text = "🎤 멀티발성키 화자분리 뷰"
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        mainLayout.addView(title)

        mainLayout.addView(speakerMap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 550
        ))

        infoText.textSize = 13f
        infoText.setTextColor(Color.WHITE)
        mainLayout.addView(infoText)

        root.addView(mainLayout)

        // ---------- 프로파일러 오버레이 ----------
        val profiler = WkProfilerView(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(8, 4, 8, 4)
        }
        root.addView(
            profiler,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        )

        setContentView(root)

        WkSafetyMonitor.initialize(this)
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
            speakerMap.updateSpeakers(sorted, separator.getActiveKeys())
        } catch (e: Exception) {
            infoText.text = "분석 오류: ${e.message}"
        }
    }

    // 🔹 PCM 순환버퍼 누적
    private fun xonPcm(stereo: ShortArray) {
        for (i in stereo.indices) {
            ring[rp] = stereo[i]
            rp = (rp + 1) % ring.size
        }
        filled = (filled + stereo.size).coerceAtMost(ring.size)
        if (filled >= 2 * N && (filled % (2 * hop) == 0)) processFrameShort()
    }

    // 🔹 쇼트배열 기반 분리 처리
    private fun processFrameShort() {
        val L = ShortArray(N)
        val R = ShortArray(N)
        var idx = (rp - 2 * N + ring.size) % ring.size
        var j = 0
        while (j < 2 * N) {
            val l = ring[idx]; idx = (idx + 1) % ring.size
            val r = ring[idx]; idx = (idx + 1) % ring.size
            L[j / 2] = l
            R[j / 2] = r
            j += 2
        }

        try {
            // 🔸 정수 기반 분리기 호출
            val speakers = separator.separateInt(L, R)
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

            // 🔸 시각화 갱신
            speakerMap.updateSpeakers(sorted, separator.getActiveKeys())

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

    // ---------- 내부 프로파일러 ----------
    class WkProfilerView(context: Context) : TextView(context), Choreographer.FrameCallback {
        private var lastTime = System.nanoTime()
        private var frameCount = 0

        init {
            textSize = 11f
            setTextColor(Color.GREEN)
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = System.nanoTime()
            if (now - lastTime > 1_000_000_000L) {
                val fps = frameCount
                val mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val kb = mem / 1024
                val threads = Thread.activeCount()
                text = "FPS=$fps  MEM=${kb}KB  THR=$threads"
                frameCount = 0
                lastTime = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}

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
import ai.willkim.wkwhisperkey.audio.WkMicArrayManager
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor
import ai.willkim.wkwhisperkey.ui.WkPhaseScatterView
import kotlin.math.*

class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
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

    // --- 토큰 안정화 ---
    private var lastTokenL = 0
    private var lastTokenR = 0
    private var stableCountL = 0
    private var stableCountR = 0
    private var confirmedTokenL = 0
    private var confirmedTokenR = 0

    private data class Row(
        val label: TextView,
        val leftBar: ProgressBar,
        val rightBar: ProgressBar,
        val values: TextView
    )
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ui.title.text = "8밴드 위상 시프트 게이지"
        ui.center.text = "← 화자 왼쪽 | 중앙 | 화자 오른쪽 →"

        for (i in bands.indices) {
            rows += ui.addBandRow("f${i} = ${bands[i].toInt()} Hz")
        }

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)
        noiseFloor = DoubleArray(bands.size) { 1e-9 }

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
        if (filled >= 2 * N && (filled % (2 * hop) == 0)) processFrame()
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

        val AVG = DoubleArray(N)
        val repL = DoubleArray(N)
        val repR = DoubleArray(N)
        for (i in 0 until N) {
            val avg = (L[i] + R[i]) * 0.5
            AVG[i] = avg
            repL[i] = L[i] //- avg
            repR[i] = R[i] //- avg
        }

        for (i in 0 until N) {
            val w = win[i]
            repL[i] *= w
            repR[i] *= w
            AVG[i] *= w
        }

        val resL = analyzeBands(repL)
        val resR = analyzeBands(repR)
        val resA = analyzeBands(AVG)

        val alphaNoise = 0.01
        val energiesRoot = resA.map { it.first }.toDoubleArray()
        for (k in bands.indices)
            noiseFloor[k] = (1 - alphaNoise) * noiseFloor[k] + alphaNoise * energiesRoot[k]

        fun energyMinusNoise(e: Double, nf: Double): Double = max(e - nf, 0.0)

        main.post {
            val phaseL = DoubleArray(bands.size)
            val phaseR = DoubleArray(bands.size)
            val amp = DoubleArray(bands.size)

            for (k in bands.indices) {
                val row = rows[k]
                val (lMag, lPhase) = resL[k]
                val (rMag, rPhase) = resR[k]
                val (aMag, _) = resA[k]

                val lCorr = energyMinusNoise(lMag, noiseFloor[k])
                val rCorr = energyMinusNoise(rMag, noiseFloor[k])
                val aCorr = energyMinusNoise(aMag, noiseFloor[k])

                val lDb = toDbSpl(lCorr)
                val rDb = toDbSpl(rCorr)
                val aDb = toDbSpl(aCorr)

                var dPhi = rPhase - lPhase
                if (dPhi > 180) dPhi -= 360.0
                if (dPhi < -180) dPhi += 360.0

                val offsetPx = 0 //(dPhi / 180.0 * 30.0).toFloat()

                row.label.translationX = offsetPx
                row.values.translationX = offsetPx
                row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
                row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
                row.values.text = String.format(
                    "AVG %6.1f | L %6.1f φ %+04.0f | R %6.1f φ %+04.0f | Δφ %+04.0f°",
                    aDb, lDb, lPhase, rDb, rPhase, dPhi
                )

                phaseL[k] = lPhase
                phaseR[k] = rPhase
                amp[k] = aDb
            }

            ui.phaseGraph.onFrame(phaseL, amp)
            ui.phaseGraph.onFrame(phaseR, amp)

            val tokenL = makeToken(resL)
            val tokenR = makeToken(resR)
            onTokenStable('L', tokenL)
            onTokenStable('R', tokenR)
        }
    }

    private fun onTokenStable(side: Char, token: Int) {
        if (side == 'L') {
            if (token == lastTokenL) stableCountL++ else { lastTokenL = token; stableCountL = 1 }
            if (stableCountL >= 2 && token != confirmedTokenL) {
                confirmedTokenL = token
                ui.addToken('L', token)
            }
        } else {
            if (token == lastTokenR) stableCountR++ else { lastTokenR = token; stableCountR = 1 }
            if (stableCountR >= 2 && token != confirmedTokenR) {
                confirmedTokenR = token
                ui.addToken('R', token)
            }
        }
    }

    private fun makeToken(bands: List<Pair<Double, Double>>): Int {
        var token = 0
        for ((i, b) in bands.withIndex()) {
            val e = toDbSpl(b.first)
            val bits = when {
                e < 40 -> 0b00
                e < 70 -> 0b01
                e < 100 -> 0b10
                else -> 0b11
            }
            token = (token shl 2) or bits
        }
        return token and 0xFFFF
    }

    private fun analyzeBands(x: DoubleArray): List<Pair<Double, Double>> {
        val Nlocal = x.size
        val out = ArrayList<Pair<Double, Double>>(bands.size)
        for (f in bands) {
            val w = 2.0 * Math.PI * f / sampleRate
            val cw = cos(w)
            val sw = sin(w)
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
            val coeff = 2.0 * cw
            for (n in 0 until Nlocal) {
                s0 = x[n] + coeff * s1 - s2
                s2 = s1; s1 = s0
            }
            val real = s1 - s2 * cw
            val imag = s2 * sw
            val mag = sqrt(real * real + imag * imag) / (Nlocal / 2.0 + 1e-9)
            val phase = Math.toDegrees(atan2(imag, real))
            out += mag to phase
        }
        return out
    }

    private fun toDbSpl(mag: Double): Double {
        val norm = (mag / 32768.0).coerceIn(1e-9, 1.0)
        return 20.0 * log10(norm) + 120.0
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
                LinearLayout.LayoutParams.MATCH_PARENT, 320
            )
        }.also { root.addView(it) }

        private val tokenLeft = TextView(act).apply {
            text = "L: "
            textSize = 14f
            setHorizontallyScrolling(true)
            isSingleLine = true
        }.also { root.addView(it) }

        private val tokenRight = TextView(act).apply {
            text = "R: "
            textSize = 14f
            setHorizontallyScrolling(true)
            isSingleLine = true
        }.also { root.addView(it) }

        fun addToken(side: Char, token: Int) {
            val s = String.format("%04X ", token)
            val tv = if (side == 'L') tokenLeft else tokenRight
            val txt = tv.text.toString() + s
            tv.text = if (txt.length > 150) txt.takeLast(150) else txt
        }

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
                    "AVG 0.0 | L 0.0 φ +000 | R 0.0 φ +000 | Δφ +000°"
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(valueText)
            return Row(label, left, right, valueText)
        }
    }
}

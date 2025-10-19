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

    private data class Row(
        val label: TextView,
        val leftBar: ProgressBar,
        val rightBar: ProgressBar,
        val values: TextView
    )
    private val rows = mutableListOf<Row>()

    private lateinit var tracker: SpeakerTracker

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
        tracker = SpeakerTracker()

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

        // 평균/대표 계산
        val AVG = DoubleArray(N)
        val repL = DoubleArray(N)
        val repR = DoubleArray(N)
        for (i in 0 until N) {
            val avg = (L[i] + R[i]) * 0.5
            AVG[i] = avg
            repL[i] = L[i] - avg
            repR[i] = R[i] - avg
        }

        // 윈도우 적용
        for (i in 0 until N) {
            val w = win[i]
            repL[i] *= w
            repR[i] *= w
            AVG[i] *= w
        }

        val resL = analyzeBands(repL)
        val resR = analyzeBands(repR)
        val resA = analyzeBands(AVG)

        // --- 화자분석 (사양서 기반) ---
        val bandsList = mutableListOf<Band>()
        for (k in bands.indices) {
            val (lMag, lPhase) = resL[k]
            val (rMag, rPhase) = resR[k]
            var dphi = Math.toRadians(rPhase - lPhase)
            if (dphi > Math.PI) dphi -= 2.0 * Math.PI
            if (dphi < -Math.PI) dphi += 2.0 * Math.PI
            val magLin = 0.5 * (lMag + rMag)
            bandsList += Band(bands[k], dphi, magLin)
        }

        val (tau, r2) = estimateTdoa(bandsList)
        val avgDb = resA.map { toDbSpl(it.first) }.average()
        val prevSide = tracker.side
        val newSide = tracker.update(tau, r2, avgDb)
        val side = if (tracker.allowHandover(prevSide, newSide)) newSide else prevSide

        val token = packToken(resA.map { toDbSpl(it.first) }.toDoubleArray())

        // UI 업데이트
        main.post {
            ui.header.text = "τ=${"%.0f".format(tracker.tauUs)}µs | R²=${"%.2f".format(tracker.r2)} | 화자=${tracker.side} | Token=0x${token.toString(16).uppercase()}"
            for (k in bands.indices) {
                val row = rows[k]
                val (lMag, lPhase) = resL[k]
                val (rMag, rPhase) = resR[k]
                val (aMag, _) = resA[k]

                val lDb = toDbSpl(lMag)
                val rDb = toDbSpl(rMag)
                val aDb = toDbSpl(aMag)

                var dPhi = rPhase - lPhase
                if (dPhi > 180) dPhi -= 360.0
                if (dPhi < -180) dPhi += 360.0
                val offsetPx = (dPhi / 180.0 * 30.0).toFloat()

                row.label.translationX = offsetPx
                row.values.translationX = offsetPx
                row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt()
                row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt()
                row.values.text = "AVG ${fmtDb(aDb)} | L ${fmtDb(lDb)}, φ ${fmtDeg(lPhase)}° | R ${fmtDb(rDb)}, φ ${fmtDeg(rPhase)}° | Δφ ${fmtDeg(dPhi)}°"
            }
        }
    }

    // ---------- FFT 밴드 분석 ----------
    private fun analyzeBands(x: DoubleArray): List<Pair<Double, Double>> {
        val Nlocal = x.size
        val out = ArrayList<Pair<Double, Double>>(bands.size)
        for (f in bands) {
            val w = 2.0 * Math.PI * f / sampleRate
            val cw = cos(w)
            val sw = sin(w)
            var s0 = 0.0
            var s1 = 0.0
            var s2 = 0.0
            val coeff = 2.0 * cw
            for (n in 0 until Nlocal) {
                s0 = x[n] + coeff * s1 - s2
                s2 = s1
                s1 = s0
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

    private fun fmtDb(v: Double) = String.format("%.1f dB", v)
    private fun fmtDeg(v: Double) = String.format("%.0f", v)

    // ---------- 화자추정/토큰 ----------

    data class Band(val f: Double, val dphi: Double, val mag: Double)

    private fun estimateTdoa(bands: List<Band>): Pair<Double, Double> {
        val w = bands.map { it.mag * it.mag }
        val num = bands.indices.sumOf { w[it] * bands[it].f * bands[it].dphi }
        val den = bands.indices.sumOf { w[it] * bands[it].f * bands[it].f }
        var tau = (num / den) / (2.0 * Math.PI)
        val step = 2.0e-5
        val span = 30
        var bestTau = tau; var bestJ = Double.MAX_VALUE
        for (m in -span..span) {
            val tt = tau + m * step
            var J = 0.0
            for (i in bands.indices) {
                val err = angdiff(bands[i].dphi - 2.0 * Math.PI * bands[i].f * tt)
                J += w[i] * err * err
            }
            if (J < bestJ) { bestJ = J; bestTau = tt }
        }
        var re = 0.0; var im = 0.0; val wsum = w.sum()
        for (i in bands.indices) {
            val e = angdiff(bands[i].dphi - 2.0 * Math.PI * bands[i].f * bestTau)
            re += w[i] * cos(e); im += w[i] * sin(e)
        }
        val R = sqrt(re*re + im*im) / wsum
        return bestTau to R*R
    }

    private fun angdiff(x: Double): Double {
        var y = x % (2.0 * Math.PI)
        if (y >  Math.PI) y -= 2.0 * Math.PI
        if (y < -Math.PI) y += 2.0 * Math.PI
        return y
    }

    private class SpeakerTracker(
        private val tauCenterUs: Double = 80.0,
        private val tauHandoverUs: Double = 150.0,
        private val ema: Double = 0.2,
        private val minR2: Double = 0.85,
        private val minDb: Double = 20.0
    ) {
        enum class Side { LEFT, CENTER, RIGHT, UNKNOWN }
        var tauUs: Double = 0.0; private set
        var side: Side = Side.UNKNOWN; private set
        var r2: Double = 0.0; private set

        fun update(tau: Double, r2in: Double, avgDb: Double): Side {
            if (r2in < minR2 || avgDb < minDb) return side
            tauUs = (1.0 - ema) * tauUs + ema * (tau * 1e6)
            r2 = r2in
            val absTau = abs(tauUs)
            side = when {
                absTau < tauCenterUs -> Side.CENTER
                tauUs > 0 -> Side.LEFT
                else -> Side.RIGHT
            }
            return side
        }

        fun allowHandover(prev: Side, next: Side): Boolean {
            if (prev == next) return true
            return abs(tauUs) > tauHandoverUs
        }
    }

    private fun energyTo2bit(db: Double): Int = when {
        db < -55 -> 0
        db < -40 -> 1
        db < -25 -> 2
        else -> 3
    }

    private fun packToken(energiesDb: DoubleArray): Int {
        var t = 0
        for (i in 0 until 8) {
            val e2 = energyTo2bit(energiesDb[i])
            t = t or (e2 shl (i*2))
        }
        return t and 0xFFFF
    }

    // ---------- 공용 ----------
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
        val header = TextView(act).apply {
            textSize = 14f; gravity = Gravity.CENTER_HORIZONTAL
        }.also { root.addView(it) }
        val center = TextView(act).apply {
            textSize = 14f; gravity = Gravity.CENTER_HORIZONTAL
        }.also { root.addView(it) }

        fun addBandRow(text: String): Row {
            val label = TextView(act).apply { this.text = text; textSize = 15f }
            root.addView(label)
            val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            val left = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; scaleX = -1f }
            val right = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            line.addView(left, lp); line.addView(right, lp)
            root.addView(line)
            val valueText = TextView(act).apply {
                this.text = "AVG 0.0 dB | L 0.0 dB, φ 0° | R 0.0 dB, φ 0°"
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(valueText)
            return Row(label, left, right, valueText)
        }
    }
}

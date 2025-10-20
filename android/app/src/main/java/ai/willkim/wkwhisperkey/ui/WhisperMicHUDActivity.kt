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

    // 장기 기준잡음 및 누적
    private val baseline = DoubleArray(bands.size) { 1e-9 }
    private val secAccum = DoubleArray(bands.size) { 0.0 }
    private var secCount = 0
    private var lastSecTs = 0L

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
        lastSecTs = SystemClock.elapsedRealtime()

        for (i in bands.indices) rows += ui.addBandRow("f${i} = ${bands[i].toInt()} Hz")

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)

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

        // ---------- 노이즈 적응 (1초 평균→장기 EMA) ----------
        val energiesRoot = resA.map { it.first }.toDoubleArray()
        for (k in bands.indices) secAccum[k] += energiesRoot[k]
        secCount++

        val now = SystemClock.elapsedRealtime()
        if (now - lastSecTs >= 1000L) {
            val denom = max(secCount, 1)
            val secAvg = DoubleArray(bands.size) { i -> secAccum[i] / denom }
            for (k in bands.indices)
                baseline[k] = 0.01 * secAvg[k] + 0.99 * baseline[k]
            java.util.Arrays.fill(secAccum, 0.0)
            secCount = 0
            lastSecTs = now
        }

        fun energyMinusNoise(e: Double, nf: Double) = max(e - nf, 0.0)
        fun gate(e: Double, base: Double) = if (e < 2.0 * base) 0.0 else e

        // ---------- UI 업데이트 ----------
        main.post {
            val phaseL = DoubleArray(bands.size) { resL[it].second }
            val phaseR = DoubleArray(bands.size) { resR[it].second }
            val amp = DoubleArray(bands.size) { max(resA[it].first - baseline[it], 1e-9) }

            for (k in bands.indices) {
                val row = rows[k]
                val (lMag, lPhase) = resL[k]
                val (rMag, rPhase) = resR[k]
                val (aMag, _) = resA[k]

                val lDb = toDbSpl(gate(energyMinusNoise(lMag, baseline[k]), baseline[k]))
                val rDb = toDbSpl(gate(energyMinusNoise(rMag, baseline[k]), baseline[k]))
                val aDb = toDbSpl(gate(energyMinusNoise(aMag, baseline[k]), baseline[k]))

                var dPhi = rPhase - lPhase
                if (dPhi > 180) dPhi -= 360.0
                if (dPhi < -180) dPhi += 360.0
                val offsetPx = (dPhi / 180.0 * 30.0).toFloat()

                row.label.translationX = offsetPx
                row.values.translationX = offsetPx
                row.leftBar.progress = ((lDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
                row.rightBar.progress = ((rDb / 120.0) * 100).roundToInt().coerceIn(0, 100)
                row.values.text = String.format(
                    "AVG %6.1f dB | L %6.1f dB, φ %+04.0f° | R %6.1f dB, φ %+04.0f° | Δφ %+04.0f°",
                    aDb, lDb, lPhase, rDb, rPhase, dPhi
                )
            }

            // 위상 점 그래프
            ui.phaseGraph.onFrame(phaseL, amp)
            ui.phaseGraph.onFrame(phaseR, amp)

            // ---------- 토큰 계산 및 표시 ----------
            val tokenRoot = tokenFrom(resA)
            val tokenL = tokenFrom(resL)
            val tokenR = tokenFrom(resR)
            ui.tokenView.text = "ROOT  0x${"%04X".format(tokenRoot)}\n" +
                                "REP0  0x${"%04X".format(tokenL)}\n" +
                                "REP1  0x${"%04X".format(tokenR)}"
        }
    }

    // ------------------------ TOKEN LOGIC ------------------------
    private fun q2(relDb: Double, active: Boolean): Int =
        if (!active) 0 else when {
            relDb >= -3.0  -> 3
            relDb >= -9.0  -> 2
            relDb >= -15.0 -> 1
            else -> 0
        }

    private fun tokenFrom(res: List<Pair<Double, Double>>): Int {
        val eCorr = DoubleArray(8) { k -> max(res[k].first - baseline[k], 1e-9) }
        val ref = eCorr[1] // 700 Hz 기준
        var t = 0
        for (k in 0 until 8) {
            val active = eCorr[k] >= 2.0 * baseline[k]
            val relDb = 20.0 * log10(eCorr[k] / ref)
            val lvl = q2(relDb, active)
            t = t or (lvl shl (k * 2))
        }
        return t and 0xFFFF
    }

    // ------------------------ DSP CORE ------------------------
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

    // ------------------------ UI ------------------------
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

        val tokenView = TextView(act).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.START
            text = "ROOT  0x0000\nREP0  0x0000\nREP1  0x0000"
        }.also { root.addView(it) }

        fun addBandRow(text: String): Row {
            val label = TextView(act).apply { this.text = text; textSize = 15f }
            root.addView(label)
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; weightSum = 2f
            }
            val left = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0; scaleX = -1f
            }
            val right = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 0
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            line.addView(left, lp); line.addView(right, lp); root.addView(line)
            val valueText = TextView(act).apply {
                this.text = "AVG  0.0 dB | L  0.0 dB, φ +000° | R  0.0 dB, φ +000° | Δφ +000°"
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(valueText)
            return Row(label, left, right, valueText)
        }
    }
}

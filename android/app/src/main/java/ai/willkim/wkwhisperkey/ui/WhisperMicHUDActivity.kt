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

    // ---------- 분석 파라미터 (사양서) ----------
    private val sampleRate = 44100                      // ≥16 kHz OK
    private val frameMs = 20
    private val N = (sampleRate * frameMs / 1000.0).roundToInt()   // ≈ 882
    private val hop = N / 2                                           // 50% overlap
    private val bands = doubleArrayOf(150.0, 700.0, 1100.0, 1700.0, 2500.0, 3600.0, 5200.0, 7500.0)

    // 슬라이딩 버퍼(스테레오: L,R interleaved)
    private val ring = ShortArray(4 * N)
    private var rp = 0
    private var filled = 0

    // Kaiser/한닝 등 윈도우(한닝)
    private val win = DoubleArray(N) { i -> 0.5 - 0.5 * cos(2.0 * Math.PI * i / (N - 1)) }

    // UI 행 구조
    private data class Row(
        val label: TextView,
        val leftBar: ProgressBar,   // 좌대표: 왼쪽으로 채우기 효과를 위해 반대편 padding으로 연출
        val rightBar: ProgressBar,  // 우대표
        val values: TextView
    )
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        // 상단 타이틀
        ui.title.text = "8-밴드 복소 스펙트럼 (평균/좌대표/우대표)"
        // 중앙 정렬 라벨
        ui.center.text = "← 좌대표    |    우대표 →"

        // 8개 밴드 라인 생성
        for (i in bands.indices) {
            rows += ui.addBandRow("f${i} = ${bands[i].toInt()} Hz")
        }

        ensureMicPermission()

        WkSafetyMonitor.initialize(this)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buf -> onPcm(buf) },   // ShortArray stereo interleaved
            onEnergyLevel = { _, _ -> }            // 사용 안함
        )

        main.postDelayed({ startMic() }, 600)
    }

    private fun startMic() {
        try {
            Toast.makeText(this, "🎤 스테레오 수집 시작", Toast.LENGTH_SHORT).show()
            micManager.startStereo()
        } catch (e: Exception) {
            Toast.makeText(this, "시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- PCM 수신 ----------
    private fun onPcm(stereo: ShortArray) {
        // 링버퍼 적재
        val n = stereo.size
        for (i in 0 until n) {
            ring[rp] = stereo[i]
            rp = (rp + 1) % ring.size
        }
        filled = (filled + n).coerceAtMost(ring.size)

        // hop만큼 쌓였으면 프레임 분석
        if (filled >= 2 * N && (filled % (2 * hop) == 0)) {
            // 최신 N*2 샘플에서 L/R 추출 → 평균/대표 계산 후 분석
            val L = DoubleArray(N)
            val R = DoubleArray(N)

            // 링에서 최신 프레임 복원 (stereo interleaved)
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

            // 평균/대표
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
                AVG[i]  *= w
            }

            // 8밴드 Goertzel(복소) → 좌/우 대표 에너지·위상, 평균 에너지·위상
            val resAvg = analyzeBands(AVG)
            val resL   = analyzeBands(repL)
            val resR   = analyzeBands(repR)

            // UI 갱신: 한 줄당 좌/우 대표 게이지, 텍스트에 E(dB SPL)·φ(°)
            main.post {
                for (k in bands.indices) {
                    val row = rows[k]

                    val (aMag, aPhase) = resAvg[k]
                    val (lMag, lPhase) = resL[k]
                    val (rMag, rPhase) = resR[k]

                    val aDb = toDbSpl(aMag)
                    val lDb = toDbSpl(lMag)
                    val rDb = toDbSpl(rMag)

                    // 게이지: 0~120 dB → 0~100%
                    row.leftBar.progress = ((lDb.coerceIn(0.0, 120.0) / 120.0) * 100).roundToInt()
                    row.rightBar.progress = ((rDb.coerceIn(0.0, 120.0) / 120.0) * 100).roundToInt()

                    row.values.text =
                        "AVG ${fmtDb(aDb)} | L ${fmtDb(lDb)}, φ ${fmtDeg(lPhase)}°  |  R ${fmtDb(rDb)}, φ ${fmtDeg(rPhase)}°"
                }
            }
        }
    }

    // ---------- Goertzel 복소 스펙트럼 8밴드 ----------
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
            // 복소 성분 (DC 보정 없음)
            val real = s1 - s2 * cw
            val imag = s2 * sw

            // 정규화: 단일 정현파 진폭≈|Y|/ (N/2)
            val mag = sqrt(real * real + imag * imag) / (Nlocal / 2.0 + 1e-9)
            val phase = Math.toDegrees(atan2(imag, real))  // -180~+180
            out += mag to phase
        }
        return out
    }

    // ---------- dB SPL 변환 (상한 120 dB = full-scale 가정) ----------
    private fun toDbSpl(mag: Double): Double {
        val norm = (mag / 32768.0).coerceIn(1e-9, 1.0) // 16-bit 기준 정규화
        return 20.0 * log10(norm) + 120.0              // 0~120 dB SPL
    }
    private fun fmtDb(v: Double) = String.format("%.1f dB")
    private fun fmtDeg(v: Double) = String.format("%.0f", v)

    // ---------- 권한 ----------
    private fun ensureMicPermission() {
        val p = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(p), 101)
    }
    override fun onRequestPermissionsResult(c:Int, perms:Array<out String>, res:IntArray){
        super.onRequestPermissionsResult(c,perms,res)
        if (c==101 && res.firstOrNull()==PackageManager.PERMISSION_GRANTED)
            main.postDelayed({ startMic() }, 500)
        else Toast.makeText(this,"권한 필요",Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }

    // ---------- 미니 UI 빌더 ----------
    private class Ui(private val act: AppCompatActivity){
        val root = LinearLayout(act).apply{
            orientation = LinearLayout.VERTICAL
            setPadding(16,16,16,16)
        }
        val title = TextView(act).apply{
            textSize = 18f
        }.also{ root.addView(it) }
        val center = TextView(act).apply{
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }.also{ root.addView(it) }

        fun addBandRow(text:String): Row {
            val label = TextView(act).apply { this.text = text; textSize = 15f }
            root.addView(label)

            // 중앙 기준 좌/우 게이지
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val left = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply{
                max = 100; progress = 0
            }
            val right = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply{
                max = 100; progress = 0
            }

            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            line.addView(left, lp)
            line.addView(right, lp)
            root.addView(line)

            val valuesText = TextView(act).apply { this.text = "AVG 0.0 dB | L 0.0 dB, φ 0° | R 0.0 dB, φ 0°" }
            root.addView(valuesText)

            return Row(label, left, right, valuesText)
        }
    }
}

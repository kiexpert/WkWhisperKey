package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
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
    private lateinit var gaugeLayout: LinearLayout
    private lateinit var txtSummary: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    // 게이지 구성요소
    private data class ChannelGauge(
        val label: TextView,
        val bar: ProgressBar,
        val value: TextView
    )

    private val gauges = mutableMapOf<String, ChannelGauge>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 기본 UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        txtSummary = TextView(this).apply {
            text = "🎧 루트화자 로그 게이지 (평균 / 좌대표 / 우대표)"
            textSize = 18f
        }
        layout.addView(txtSummary)

        gaugeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(gaugeLayout)
        setContentView(layout)

        ensureMicPermission()
        setupGaugeUI()

        WkSafetyMonitor.initialize(this)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, buffer -> updateWaveEnergy(buffer) },
            onEnergyLevel = { _, _ -> }
        )

        mainHandler.postDelayed({ startMic() }, 800)
    }

    private fun setupGaugeUI() {
        fun addGauge(label: String): ChannelGauge {
            val name = TextView(this).apply { text = label; textSize = 16f }
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            val valTxt = TextView(this).apply { text = "0.0 dB SPL" }
            gaugeLayout.addView(name)
            gaugeLayout.addView(bar)
            gaugeLayout.addView(valTxt)
            return ChannelGauge(name, bar, valTxt)
        }
        gauges["avg"] = addGauge("🎧 평균음")
        gauges["left"] = addGauge("🎙️ 좌대표")
        gauges["right"] = addGauge("🎙️ 우대표")
    }

    private fun startMic() {
        try {
            Toast.makeText(this, "🎤 스테레오 마이크 시작 중...", Toast.LENGTH_SHORT).show()
            micManager.startStereo()
        } catch (e: Exception) {
            Toast.makeText(this, "마이크 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("WhisperMicHUD", "❌ startMic error", e)
        }
    }

    /** 🎚️ 각 파형별 RMS → dB SPL 변환 및 게이지 갱신 */
    private fun updateWaveEnergy(buffer: ShortArray) {
        if (buffer.isEmpty()) return
        val n = buffer.size
        var sumL = 0.0
        var sumR = 0.0

        val avgBuf = ShortArray(n / 2)
        var j = 0
        var i = 0
        while (i < n - 1) {
            val l = buffer[i].toInt()
            val r = buffer[i + 1].toInt()
            avgBuf[j] = ((l + r) / 2).toShort()
            sumL += l * l
            sumR += r * r
            j++; i += 2
        }

        val avgRms = sqrt(avgBuf.map { it * it }.average())
        val leftRms = sqrt(sumL / (n / 2))
        val rightRms = sqrt(sumR / (n / 2))

        // 대표파형 (평균으로부터의 차)
        var sumLeftDiff = 0.0
        var sumRightDiff = 0.0
        for (k in avgBuf.indices) {
            val l = buffer[k * 2].toInt()
            val r = buffer[k * 2 + 1].toInt()
            sumLeftDiff += (l - avgBuf[k]) * (l - avgBuf[k])
            sumRightDiff += (r - avgBuf[k]) * (r - avgBuf[k])
        }
        val leftDiffRms = sqrt(sumLeftDiff / avgBuf.size)
        val rightDiffRms = sqrt(sumRightDiff / avgBuf.size)

        updateGaugeAsSPL("avg", avgRms)
        updateGaugeAsSPL("left", leftDiffRms)
        updateGaugeAsSPL("right", rightDiffRms)
    }

    /** 🎚️ RMS → dB SPL 변환 (120 dB SPL 상한 정규화) */
    private fun updateGaugeAsSPL(key: String, rms: Double) {
        val norm = (rms / 32768.0).coerceIn(1e-9, 1.0)

        // 절대 dB SPL 계산: 120 dB SPL ≈ 32767일 때
        val spl = 20 * log10(norm) + 120.0
        val percent = (spl.coerceIn(0.0, 120.0) / 120.0 * 100).roundToInt()

        mainHandler.post {
            gauges[key]?.apply {
                bar.progress = percent
                value.text = String.format("%.1f dB SPL", spl)
            }
        }
    }

    private fun ensureMicPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "🎤 마이크 권한 허용됨", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ startMic() }, 500)
        } else {
            Toast.makeText(this, "❌ 마이크 권한이 필요합니다", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }
}

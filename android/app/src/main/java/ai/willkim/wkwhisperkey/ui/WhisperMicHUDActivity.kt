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

    private data class ChannelGauge(
        val label: TextView,
        val bar: ProgressBar,
        val value: TextView
    )

    private val gauges = mutableMapOf<String, ChannelGauge>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        txtSummary = TextView(this).apply {
            text = "루트화자 로그 게이지 (평균 / 좌대표 / 우대표)"
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
            val valTxt = TextView(this).apply { text = "-∞ dB" }
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

    /** 🎚️ 각 파형별 에너지 계산 및 로그게이지 갱신 */
    private fun updateWaveEnergy(buffer: ShortArray) {
        if (buffer.isEmpty()) return
        val read = buffer.size
        var sumL = 0.0
        var sumR = 0.0

        // 좌우 평균 계산
        val avgBuf = ShortArray(read / 2)
        var j = 0
        var i = 0
        while (i < read - 1) {
            val l = buffer[i].toInt()
            val r = buffer[i + 1].toInt()
            avgBuf[j] = ((l + r) / 2).toShort()
            sumL += l * l
            sumR += r * r
            j++; i += 2
        }

        // 평균 RMS
        val avgRms = sqrt(avgBuf.map { it * it }.average())
        val leftRms = sqrt(sumL / (read / 2))
        val rightRms = sqrt(sumR / (read / 2))

        // 대표파형 RMS
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

        updateGauge("avg", avgRms)
        updateGauge("left", leftDiffRms)
        updateGauge("right", rightDiffRms)
    }

    private fun updateGauge(key: String, rms: Double) {
        val norm = (rms / 32768.0).coerceIn(1e-6, 1.0)
        val db = 20 * log10(norm)
        // 로그 스케일 게이지 (0dB = 최대, -80dB = 하한)
        val scaled = ((db + 80f) / 80f).coerceIn(0.0, 1.0)
        val percent = (scaled * 100).roundToInt()

        mainHandler.post {
            gauges[key]?.apply {
                bar.progress = percent
                value.text = String.format("%.1f dB", db)
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

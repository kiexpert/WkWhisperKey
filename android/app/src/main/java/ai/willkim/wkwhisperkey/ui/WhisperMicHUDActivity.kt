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
    private val mainHandler = Handler(Looper.getMainLooper())

    // 평균 + 좌 + 우 텍스트
    private lateinit var txtAvg: TextView
    private lateinit var txtLeft: TextView
    private lateinit var txtRight: TextView

    // 게이지 3개
    private lateinit var gaugeAvg: ProgressBar
    private lateinit var gaugeLeft: ProgressBar
    private lateinit var gaugeRight: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        // 평균 음원 게이지
        txtAvg = TextView(this).apply { text = "평균 음원 (RMSavg)" }
        gaugeAvg = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        layout.addView(txtAvg)
        layout.addView(gaugeAvg)

        // 좌 마이크 대표
        txtLeft = TextView(this).apply { text = "좌 대표 음원 (L-avg)" }
        gaugeLeft = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        layout.addView(txtLeft)
        layout.addView(gaugeLeft)

        // 우 마이크 대표
        txtRight = TextView(this).apply { text = "우 대표 음원 (R-avg)" }
        gaugeRight = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        layout.addView(txtRight)
        layout.addView(gaugeRight)

        setContentView(layout)

        ensureMicPermission()

        WkSafetyMonitor.initialize(this)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, _ -> WkSafetyMonitor.heartbeat() },
            onEnergyLevel = { _, level -> updateMicEnergy(level) }
        )

        mainHandler.postDelayed({ micManager.startStereo() }, 800)
    }

    // 🟢 에너지 업데이트
    private fun updateMicEnergy(level: Float) {
        // WkMicArrayManager에서 통합 level만 넘어오므로 L/R 계산 루틴을 직접 넣음
        val leftEnergy = micManager.lastLeftEnergy
        val rightEnergy = micManager.lastRightEnergy
        val avg = (leftEnergy + rightEnergy) / 2f

        val diffL = (leftEnergy - avg).coerceAtLeast(0f)
        val diffR = (rightEnergy - avg).coerceAtLeast(0f)

        val scaledAvg = logScale(avg)
        val scaledL = logScale(diffL)
        val scaledR = logScale(diffR)

        mainHandler.post {
            txtAvg.text = "평균 음원: ${"%.3f".format(avg)}"
            txtLeft.text = "좌 대표: Δ${"%.3f".format(diffL)}"
            txtRight.text = "우 대표: Δ${"%.3f".format(diffR)}"

            gaugeAvg.progress = (scaledAvg * 100).roundToInt()
            gaugeLeft.progress = (scaledL * 100).roundToInt()
            gaugeRight.progress = (scaledR * 100).roundToInt()
        }
    }

    private fun logScale(v: Float): Float {
        if (v <= 0f) return 0f
        return (ln(1 + v * 1000) / ln(1001.0)).toFloat().coerceIn(0f, 1f)
    }

    // 🔒 권한
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
        if (requestCode == 101 && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "🎤 마이크 권한 허용됨", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ micManager.startStereo() }, 500)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }
}

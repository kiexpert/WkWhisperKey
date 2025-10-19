package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
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
import kotlin.math.roundToInt

class WhisperMicHUDActivity : AppCompatActivity() {

    private lateinit var micManager: WkMicArrayManager
    private val micGauges = mutableMapOf<Int, ProgressBar>()
    private lateinit var gaugeLayout: LinearLayout
    private lateinit var txtEnergy: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        txtEnergy = TextView(this).apply { text = "통합 채널 에너지: 0%" }
        layout.addView(txtEnergy)
        gaugeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(gaugeLayout)
        setContentView(layout)

        // ✅ 권한 확인 및 요청
        ensureMicPermission()

        WkSafetyMonitor.initialize(this)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { id, _ -> WkSafetyMonitor.heartbeat() },
            onEnergyLevel = { id, level -> updateMicEnergy(id, level) }
        )

        // 권한 허용 직후 약간의 지연 후 마이크 스캔
        mainHandler.postDelayed({ startMic() }, 600)
    }

    private fun startMic() {
        try {
            Toast.makeText(this, "🎤 마이크 스캔 중...", Toast.LENGTH_SHORT).show()
            val inputs = micManager.scanInputs()
            gaugeLayout.removeAllViews()
            for (d in inputs) addMicGauge(d)
            micManager.startStereo()
        } catch (e: Exception) {
            Toast.makeText(this, "마이크 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addMicGauge(dev: AudioDeviceInfo) {
        val id = dev.id
        val name = dev.productName ?: "Mic"
        val txt = TextView(this).apply { text = "🎙️ Mic $id ($name)" }
        val gauge = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        gaugeLayout.addView(txt)
        gaugeLayout.addView(gauge)
        micGauges[id] = gauge
    }

    private fun updateMicEnergy(id: Int, level: Float) {
        val percent = (level * 100).roundToInt().coerceIn(0, 100)
        mainHandler.post {
            txtEnergy.text = "통합 채널 에너지: ${percent}%"
            micGauges[id]?.progress = percent
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            micManager.deviceReceiver,
            micManager.deviceFilter
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(micManager.deviceReceiver)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }

    // ✅ 권한 체크 함수
    private fun ensureMicPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            Log.i("Permission", "🎙️ Mic permission already granted")
        }
    }

    // ✅ 권한 요청 결과 처리
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "🎤 마이크 권한 허용됨", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ startMic() }, 500)
            } else {
                Toast.makeText(this, "❌ 마이크 권한이 필요합니다", Toast.LENGTH_LONG).show()
            }
        }
    }
}

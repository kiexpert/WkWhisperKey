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

        // ---------- 기본 UI ----------
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        txtEnergy = TextView(this).apply {
            text = "통합 채널 에너지: 0%"
            textSize = 18f
        }
        layout.addView(txtEnergy)
        gaugeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(gaugeLayout)
        setContentView(layout)
        // ----------------------------

        ensureMicPermission()

        WkSafetyMonitor.initialize(this)
        micManager = WkMicArrayManager(
            this,
            onBuffer = { _, _ -> WkSafetyMonitor.heartbeat() },
            onEnergyLevel = { id, level -> updateMicEnergy(id, level) }
        )

        mainHandler.postDelayed({ startMic() }, 800)
    }

    // ---------- 마이크 시작 ----------
    private fun startMic() {
        try {
            Toast.makeText(this, "🎤 스테레오 마이크 시작 중...", Toast.LENGTH_SHORT).show()
    
            gaugeLayout.removeAllViews()
    
            // 임시 ID 0번 게이지 추가
            val txt = TextView(this).apply {
                text = "🎙️ 스테레오 마이크 (기본 입력)"
                textSize = 16f
            }
            val gauge = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            gaugeLayout.addView(txt)
            gaugeLayout.addView(gauge)
            micGauges[0] = gauge
    
            micManager.startStereo()
    
        } catch (e: Exception) {
            Toast.makeText(this, "마이크 시작 실패: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("WhisperMicHUD", "❌ startMic error", e)
        }
    }

    // ---------- 게이지 추가 ----------
    private fun addMicGauge(dev: AudioDeviceInfo) {
        val id = dev.id
        val txt = TextView(this).apply {
            text = "🎙️ 스테레오 마이크 (id=$id)"
            textSize = 16f
        }
        val gauge = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        gaugeLayout.addView(txt)
        gaugeLayout.addView(gauge)
        micGauges[id] = gauge
    }

    // ---------- 에너지 업데이트 ----------
    private fun updateMicEnergy(id: Int, level: Float) {
        val percent = (level * 100).roundToInt().coerceIn(0, 100)
        mainHandler.post {
            txtEnergy.text = "통합 채널 에너지: ${percent}%"
            micGauges[id]?.progress = percent
        }
    }

    // ---------- 권한 확인 ----------
    private fun ensureMicPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            Log.i("Permission", "🎙️ Mic permission already granted")
        }
    }

    // ---------- 권한 요청 결과 ----------
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

    // ---------- 생명주기 ----------
    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }
}

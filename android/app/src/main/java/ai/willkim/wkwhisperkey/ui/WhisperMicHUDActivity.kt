package ai.willkim.wkwhisperkey.ui

import ai.willkim.wkwhisperkey.audio.*
import ai.willkim.wkwhisperkey.whisper.*
import ai.willkim.wkwhisperkey.whisper.api.WhisperApiEngine
import ai.willkim.wkwhisperkey.whisper.local.WhisperLocalEngine
import ai.willkim.wkwhisperkey.whisper.native.WhisperCppEngine
import ai.willkim.wkwhisperkey.whisper.core.WkWhisperConsensusLog
import ai.willkim.wkwhisperkey.whisper.core.WkWhisperHybridBridge
import android.app.Activity
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import kotlin.math.roundToInt
import java.io.File

/**
 * WhisperMicHUDActivity v3.0
 * --------------------------
 * 앱 실행 시 다중 마이크 HUD + Whisper Hybrid 비교 패널을 동시에 표시.
 */
class WhisperMicHUDActivity : Activity() {

    private val micPermission = android.Manifest.permission.RECORD_AUDIO
    private val PERM_REQ = 777

    private lateinit var micManager: WkMicArrayManager
    private lateinit var multiplexer: WkAudioMultiplexer
    private lateinit var hybrid: WkWhisperHybridBridge

    private lateinit var layout: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var btnReconnect: Button
    private lateinit var txtMerged: TextView
    private lateinit var gaugeMap: MutableMap<Int, ProgressBar>

    // 비교패널
    private lateinit var txtJNI: TextView
    private lateinit var txtLocal: TextView
    private lateinit var txtAPI: TextView
    private lateinit var txtFinal: TextView
    private lateinit var barConf: ProgressBar

    private var mergedEnergy = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(micPermission) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(micPermission), PERM_REQ)
        else setupUIAndStart()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, results: IntArray) {
        if (rc == PERM_REQ && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            setupUIAndStart()
        else {
            Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupUIAndStart() {
        layout = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(root)
        setContentView(layout)
        gaugeMap = mutableMapOf()

        btnReconnect = Button(this).apply {
            text = "🔄 다시 연결"
            setOnClickListener { reconnectMics() }
        }
        txtMerged = TextView(this).apply {
            text = "통합 채널 에너지: 0%"
            textSize = 16f
        }

        root.addView(btnReconnect)
        root.addView(txtMerged)
        root.addView(makeSeparator())
        
        // 🔽 추가: 권한 승인 직후 오디오 초기화 지연
        Handler(Looper.getMainLooper()).postDelayed({
            initMicSystem()
        }, 600)
        
        initComparePanel()
    }

    private fun initMicSystem() {
        hybrid = WkWhisperHybridBridge(
            engineCpp = WhisperCppEngine(),
            engineLocal = WhisperLocalEngine(),
            engineApi = WhisperApiEngine(apiKey = System.getenv("OPENAI_API_KEY") ?: "")
        ) { text, detail ->
            runOnUiThread {
                txtJNI.text = "JNI: ${detail.cpp}"
                txtLocal.text = "Local: ${detail.local}"
                txtAPI.text = "API: ${detail.api}"
                txtFinal.text = "결정: ${detail.text}"
                barConf.progress = (detail.confidence * 100).roundToInt()
            }
            WkWhisperConsensusLog.save(detail)
        }

        multiplexer = WkAudioMultiplexer(onMerged = { pcm ->
            hybrid.feedPcm(pcm.toByteBuffer())
        })

        micManager = WkMicArrayManager(
            context = this,
            onBuffer = { _, _ -> },
            onEnergyLevel = { id, level ->
                runOnUiThread { updateGauge(id, level) }
                val buf = micManager.getLastBuffer(id)
                if (buf != null) multiplexer.onMicFrame(id, buf, level)
            }
        )
        attachMics()
    }

    private fun attachMics() {
        val mics = micManager.scanInputs()
        if (mics.isEmpty()) {
            Toast.makeText(this, "마이크를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        for (mic in mics) {
            val id = mic.id
            val gauge = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
            }
            root.addView(TextView(this).apply { text = "🎤 Mic $id (${mic.address})" })
            root.addView(gauge)
            gaugeMap[id] = gauge
        }
        micManager.startStereo()
    }

    private fun initComparePanel() {
        root.addView(makeSeparator())
        root.addView(TextView(this).apply {
            text = "🧠 Whisper Hybrid 비교"
            textSize = 18f
            setPadding(0, 24, 0, 12)
        })
        txtJNI = TextView(this)
        txtLocal = TextView(this)
        txtAPI = TextView(this)
        txtFinal = TextView(this).apply { textSize = 16f }
        barConf = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        listOf(txtJNI, txtLocal, txtAPI, txtFinal, barConf).forEach { root.addView(it) }

        val btnReplay = Button(this).apply {
            text = "📜 로그 리플레이"
            setOnClickListener { replayLogs() }
        }
        root.addView(btnReplay)
    }

    private fun updateGauge(id: Int, level: Float) {
        gaugeMap[id]?.progress = (level * 100).roundToInt()
        mergedEnergy = (mergedEnergy * 0.8f + level * 0.2f)
        txtMerged.text = "통합 채널 에너지: ${(mergedEnergy * 100).roundToInt()}%"
    }

    private fun reconnectMics() {
        try {
            micManager.stopAll()
            root.removeAllViews()
            root.addView(btnReconnect)
            root.addView(txtMerged)
            root.addView(makeSeparator())
            gaugeMap.clear()
            attachMics()
            initComparePanel()
        } catch (e: Exception) {
            Log.e("MicHUD", "Reconnect failed: ${e.message}")
        }
    }

    private fun replayLogs() {
        val dir = File("/sdcard/WkWhisperLogs")
        if (!dir.exists()) {
            Toast.makeText(this, "로그가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val recent = files.takeLast(3)
        Toast.makeText(this, "최근 로그 ${recent.size}개 표시", Toast.LENGTH_SHORT).show()
        for (f in recent) {
            val txt = f.readText()
            Log.i("ReplayLog", txt)
            txtFinal.text = txt
        }
    }

    private fun makeSeparator(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2)
        setBackgroundColor(0xFF666666.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        hybrid.stop()
    }
}

package ai.willkim.wkwhisperkey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.willkim.wkwhisperkey.audio.*
import ai.willkim.wkwhisperkey.system.WkSafetyMonitor
import kotlin.math.roundToInt

/**
 * WhisperMicHUDActivity – 화자 분리 및 토큰 시각화 허브
 */
class WhisperMicHUDActivity : AppCompatActivity() {

    private val sampleRate = 44100
    private lateinit var micManager: WkMicArrayManager
    private lateinit var voiceSeparator: WkVoiceSeparator
    private lateinit var tokenizer: WkVoiceTokenizer
    private val main = Handler(Looper.getMainLooper())
    private val ui by lazy { Ui(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ui.root)

        ensureMicPermission()
        WkSafetyMonitor.initialize(this)

        voiceSeparator = WkVoiceSeparator(sampleRate)
        tokenizer = WkVoiceTokenizer(doubleArrayOf(150.0,700.0,1100.0,1700.0,2500.0,3600.0,5200.0,7500.0))

        micManager = WkMicArrayManager(this, onBuffer = { _, buf -> onPcm(buf) }, onEnergyLevel = { _, _ -> })
        main.postDelayed({ micManager.startStereo() }, 800)
    }

    private fun onPcm(stereo: ShortArray) {
        val L = DoubleArray(stereo.size / 2)
        val R = DoubleArray(stereo.size / 2)
        var i = 0
        var j = 0
        while (i < stereo.size - 1) {
            L[j] = stereo[i].toDouble()
            R[j] = stereo[i + 1].toDouble()
            i += 2; j++
        }
        val speakers = voiceSeparator.processFrame(L, R)
        val tokens = tokenizer.tokenizeAll(speakers)
        updateUi(speakers, tokens)
    }

    private fun updateUi(list: List<WkVoiceSeparator.SpeakerInfo>, tokens: Map<Int,String>) {
        ui.listLayout.removeAllViews()
        list.forEach {
            val text = TextView(this).apply {
                text = String.format(
                    "화자 #%d | θ=%+05.1f° | d=%.2fm | E=%5.1f dB | %s",
                    it.id, it.angleDeg, it.distanceM, it.rmsDb, tokens[it.id] ?: "0x0000"
                )
                textSize = 15f
                gravity = Gravity.CENTER_HORIZONTAL
            }
            ui.listLayout.addView(text)
        }
    }

    private fun ensureMicPermission() {
        val p = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(p), 101)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (c == 101 && r.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            main.postDelayed({ micManager.startStereo() }, 500)
    }

    override fun onDestroy() {
        super.onDestroy()
        micManager.stopAll()
        WkSafetyMonitor.stop()
    }

    private class Ui(act: AppCompatActivity) {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val listLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
        }.also { root.addView(it) }
    }
}

package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.willkim.wkwhisperkey.audio.SpeakerSignal
import ai.willkim.wkwhisperkey.audio.VoiceKey
import kotlin.math.*

class WkSpeakerMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var speakers: List<SpeakerSignal> = emptyList()
    var allVoiceKeys: List<VoiceKey> = emptyList()

    private val micPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 32f
        isAntiAlias = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,165,0), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.rgb(0,0,139), Color.rgb(138,43,226)
    )

    fun updateSpeakers(speakers: List<SpeakerSignal>, voiceKeys: List<VoiceKey>) {
        this.speakers = speakers
        this.allVoiceKeys = voiceKeys
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseY = h * 0.15f

        // --- 마이크 위치 ---
        val micOffset = w * 0.25f
        canvas.drawCircle(cx - micOffset, baseY, 20f, micPaint)
        canvas.drawText("L", cx - micOffset - 12f, baseY + 60f, textPaint)
        canvas.drawCircle(cx + micOffset, baseY, 20f, micPaint)
        canvas.drawText("R", cx + micOffset - 12f, baseY + 60f, textPaint)

        // --- 거리 기반 정규화 ---
        val maxDist = (speakers.maxOfOrNull { it.distance } ?: 0.1).coerceAtLeast(0.05)
        val scale = (h * 0.6f / maxDist.toFloat())

        // --- 발성키 점 ---
        for ((i, key) in allVoiceKeys.withIndex()) {
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]
            val alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(40, 255)
            dotPaint.color = baseColor
            dotPaint.alpha = alpha

            val distM = abs(key.deltaIndex) / 44100.0 * 343.0
            val d = distM.toFloat() * scale
            val angle = ((key.deltaIndex.coerceIn(-100,100) / 100f) * 60f)
            val rad = Math.toRadians(angle.toDouble())

            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // --- 화자 원 ---
        for ((idx, spk) in speakers.take(8).withIndex()) {
            val color = colors[idx % colors.size]
            val alpha = (min(1.0, spk.energy / 80.0) * 255).toInt().coerceIn(80, 255)
            circlePaint.color = color
            circlePaint.alpha = alpha
        
            val distM = spk.distance
            val d = distM.toFloat() * scale
            val angle = ((spk.deltaIndex.coerceIn(-100,100) / 100f) * 60f)
            val rad = Math.toRadians(angle.toDouble())
        
            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
        
            canvas.drawCircle(x, y, 20f, circlePaint)
        
            // ---- 거리 표시: mm 단위 ----
            val distMm = distM * 1000.0
            val txt = String.format("%.1fmm", distMm)
            textPaint.color = color
            textPaint.alpha = 230
            canvas.drawText(txt, x - 40f, y + 40f, textPaint)
        }
    }
}

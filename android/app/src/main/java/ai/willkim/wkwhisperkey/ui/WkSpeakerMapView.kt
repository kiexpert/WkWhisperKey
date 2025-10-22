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

    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 32f
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

        // --- 거리 정규화: 최대 화자 거리 ≈ 500px ---
        val maxDistMm = speakers.maxOfOrNull { it.distance } ?: 1.0
        val targetMaxPx = 500f
        val scale = (targetMaxPx / maxDistMm).toFloat().coerceAtLeast(0.1f)

        // --- 모든 발성키 점 ---
        for ((i, key) in allVoiceKeys.withIndex()) {
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]
            val alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(40, 255)
            dotPaint.color = baseColor
            dotPaint.alpha = alpha

            val distMm = abs(key.deltaIndex) / 44100.0 * 343000.0 // mm
            val d = distMm.toFloat() * scale
            val angle = (key.deltaIndex.coerceIn(-100,100) / 100f) * 60f
            val rad = Math.toRadians(angle.toDouble())

            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // --- 대표 화자 ---
        for ((idx, spk) in speakers.take(8).withIndex()) {
            val color = colors[idx % colors.size]
            val alpha = (min(1.0, spk.energy / 80.0) * 255).toInt().coerceIn(80, 255)
            circlePaint.color = color
            circlePaint.alpha = alpha

            val distMm = spk.distance
            val d = distMm.toFloat() * scale
            val angle = (spk.deltaIndex.coerceIn(-100,100) / 100f) * 60f
            val rad = Math.toRadians(angle.toDouble())

            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 20f, circlePaint)

            // 거리 텍스트
            val txt = String.format("%.1fmm", distMm)
            textPaint.color = color
            textPaint.alpha = 230
            canvas.drawText(txt, x - 45f, y + 40f, textPaint)
        }
    }
}

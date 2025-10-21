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
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // 0~7번 화자 색상 팔레트
    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,165,0), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.rgb(0,0,139), Color.rgb(138,43,226)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val baseY = h * 0.15f

        // 마이크 위치 (좌/우)
        val micOffset = w * 0.25f
        canvas.drawCircle(centerX - micOffset, baseY, 20f, micPaint)
        canvas.drawText("L", centerX - micOffset - 12f, baseY + 60f, textPaint)
        canvas.drawCircle(centerX + micOffset, baseY, 20f, micPaint)
        canvas.drawText("R", centerX + micOffset - 12f, baseY + 60f, textPaint)

        // 최대 거리 찾기 (정규화용)
        val maxDist = (allVoiceKeys.maxOfOrNull { abs(it.deltaIndex) }?.toDouble() ?: 1.0).coerceAtLeast(1.0)
        val scale = (h * 0.65f) / maxDist.toFloat()

        // --- 모든 발성키 점 그리기 ---
        for ((i, key) in allVoiceKeys.withIndex()) {
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]
            val alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(30, 255)
            dotPaint.color = baseColor
            dotPaint.alpha = alpha

            val d = abs(key.deltaIndex).toFloat() * scale
            val angle = ((key.deltaIndex / maxDist) * 60.0).toFloat() // -60°~60° 분포
            val rad = Math.toRadians(angle.toDouble())
            val x = centerX + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f

            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // --- 대표 발성키 원 표시 ---
        for ((idx, spk) in speakers.take(8).withIndex()) {
            val color = colors[idx % colors.size]
            val alpha = (min(1.0, spk.energy / 80.0) * 255).toInt().coerceIn(80, 255)
            circlePaint.color = color
            circlePaint.alpha = alpha

            val d = abs(spk.deltaIndex).toFloat() * scale
            val angle = ((spk.deltaIndex / maxDist) * 60.0).toFloat()
            val rad = Math.toRadians(angle.toDouble())
            val x = centerX + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f

            canvas.drawCircle(x, y, 20f, circlePaint)

            val distM = abs(spk.deltaIndex) / 44100.0 * 343.0
            val txt = String.format("%.1fm", distM)
            textPaint.color = color
            textPaint.alpha = 220
            canvas.drawText(txt, x - 30f, y + 40f, textPaint)
        }
    }
}

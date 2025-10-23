package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.willkim.wkwhisperkey.audio.*
import kotlinx.coroutines.*
import kotlin.math.*

class WkSpeakerMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), CoroutineScope by MainScope() {

    private var speakers: List<SpeakerSignal> = emptyList()
    private var allVoiceKeys: List<VoiceKey> = emptyList()

    // 그래픽 도구
    private val micPaint = Paint().apply { color = Color.LTGRAY; style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.DKGRAY; textSize = 32f; isAntiAlias = true }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,165,0), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.rgb(0,0,139), Color.rgb(138,43,226)
    )

    init {
        // 자동 업데이트 루프
        launch {
            while (isActive) {
                val svc = WkVoiceSeparatorService.getInstance()
                if (svc != null) {
                    speakers = svc.getSpeakers()
                    allVoiceKeys = speakers.flatMap { it.keys }
                    postInvalidate()
                }
                delay(100) // 10fps
            }
        }
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    // -------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseY = h * 0.15f

        // 마이크 좌우
        val micOffset = w * 0.25f
        canvas.drawCircle(cx - micOffset, baseY, 20f, micPaint)
        canvas.drawCircle(cx + micOffset, baseY, 20f, micPaint)

        // 거리 스케일
        val maxDist = (speakers.maxOfOrNull { it.distance } ?: 0.1).coerceAtLeast(0.05)
        val scale = (h * 0.6f / maxDist.toFloat())

        // 모든 발성키 점
        for ((i, key) in allVoiceKeys.withIndex()) {
            val color = colors[i % colors.size]
            dotPaint.color = color
            dotPaint.alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(40, 255)
            val d = key.distanceMm.toFloat() * scale
            val angle = ((key.deltaIndex.coerceIn(-100,100) / 100f) * 60f)
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // 대표 화자 원
        for ((idx, spk) in speakers.take(8).withIndex()) {
            val color = colors[idx % colors.size]
            circlePaint.color = color
            circlePaint.alpha = (min(1.0, spk.energy / 80.0) * 255).toInt().coerceIn(80, 255)
            val d = spk.distance.toFloat() * scale
            val angle = ((spk.deltaIndex.coerceIn(-100,100) / 100f) * 60f)
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 20f, circlePaint)
            val txt = String.format("%.1fmm", spk.distance)
            textPaint.color = color
            textPaint.alpha = 230
            canvas.drawText(txt, x - 40f, y + 40f, textPaint)
        }
    }
}

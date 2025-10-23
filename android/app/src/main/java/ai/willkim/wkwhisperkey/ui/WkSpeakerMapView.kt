package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.willkim.wkwhisperkey.audio.SpeakerSignal
import ai.willkim.wkwhisperkey.audio.VoiceKey
import kotlin.math.*

/**
 * WkSpeakerMapView v2.3
 * -------------------------------------------------------------
 * - 분리기 출력(거리·델타인덱스·에너지)을 2D 좌표로 매핑
 * - 마이크 거리 및 반경(500mm) 기준 시각적 비율 유지
 * - 에너지 강도에 따라 중심부 곡률 자동 보정
 * - 강한 에너지는 중심, 약한 신호는 외곽 포물선상으로 배치
 */

class WkSpeakerMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var speakers: List<SpeakerSignal> = emptyList()
    var allVoiceKeys: List<VoiceKey> = emptyList()

    private val MIC_DISTANCE_MAX_MM = 200f
    private val CURRENT_MIC_DIST_MM = 20f
    private val MAX_DRAW_RADIUS_MM = 500f
    private val DEG_SPREAD = 60.0

    private val micPaint = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
        isAntiAlias = true
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

    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,165,0), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.rgb(0,0,139), Color.rgb(138,43,226)
    )

    // ----------------------------------------------------------
    fun updateSpeakers(speakers: List<SpeakerSignal>, voiceKeys: List<VoiceKey>) {
        this.speakers = speakers
        this.allVoiceKeys = voiceKeys

        // 🔹 UI 좌표 매핑 수행 (에너지 기반 포물선 곡면)
        if (voiceKeys.isNotEmpty()) {
            val eMax = voiceKeys.maxOf { it.energy }
            val eMin = voiceKeys.minOf { it.energy }
            val eRange = (eMax - eMin).coerceAtLeast(1e-9)

            for (key in allVoiceKeys) {
                val eNorm = ((key.energy - eMin) / eRange).coerceIn(0.0, 1.0)
                val dNorm = (key.distanceMm / MAX_DRAW_RADIUS_MM).coerceIn(0.0, 1.0)
                // 포물선형 거리 보정: 에너지가 작을수록 곡선 바깥으로
                val curvedR = (dNorm * dNorm) * MAX_DRAW_RADIUS_MM * (1.0 - eNorm) + eNorm * 20.0
                val theta = (key.deltaIndex / 600.0).coerceIn(-1.0, 1.0) * (Math.PI / 2)
                key.energyPosX = 250.0 + sin(theta) * curvedR
                key.energyPosY = 250.0 + cos(theta) * curvedR
            }
        }

        invalidate()
    }

    // ----------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseY = h * 0.15f
        val scale = (h * 0.65f) / MAX_DRAW_RADIUS_MM
        val micOffset = (CURRENT_MIC_DIST_MM * scale) / 2f

        // ---- 마이크 표시 ----
        canvas.drawCircle(cx - micOffset, baseY, 20f, micPaint)
        canvas.drawText("L", cx - micOffset - 12f, baseY + 60f, textPaint)
        canvas.drawCircle(cx + micOffset, baseY, 20f, micPaint)
        canvas.drawText("R", cx + micOffset - 12f, baseY + 60f, textPaint)

        // ---- 발성키 점 ----
        for ((i, key) in allVoiceKeys.withIndex()) {
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]
            val alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(40, 255)
            dotPaint.color = baseColor
            dotPaint.alpha = alpha

            val x = cx + (key.energyPosX - 250).toFloat() * scale * 0.8f
            val y = baseY + (key.energyPosY - 250).toFloat() * scale * 0.8f
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // ---- 화자 원 ----
        for ((idx, spk) in speakers.take(8).withIndex()) {
            val color = colors[idx % colors.size]
            val alpha = (min(1.0, spk.energy / 80.0) * 255).toInt().coerceIn(80, 255)
            circlePaint.color = color
            circlePaint.alpha = alpha

            val d = spk.distance.toFloat().coerceAtMost(MAX_DRAW_RADIUS_MM) * scale
            val angle = ((spk.deltaIndex.coerceIn(-100, 100) / 100f) * DEG_SPREAD).toFloat()
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

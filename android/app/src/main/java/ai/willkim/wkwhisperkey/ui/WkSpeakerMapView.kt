package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.willkim.wkwhisperkey.audio.SpeakerSignal
import ai.willkim.wkwhisperkey.audio.VoiceKey
import kotlin.math.*

/**
 * WkSpeakerMapView v2.1
 * -------------------------------------------------------------
 * - 마이크 간 간격 및 화자 거리 모두 실제 거리(mm) 스케일로 정규화
 * - MIC_DISTANCE_MAX_MM(200mm) 기준 자동 스케일링
 * - 세로모드(20mm) / 가로모드(150mm) / 펼침(180mm) 대비 자동 축소
 * - 시각적으로 실제 공간 비례 유지
 */

class WkSpeakerMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- 실시간 업데이트 데이터 ----
    var speakers: List<SpeakerSignal> = emptyList()
    var allVoiceKeys: List<VoiceKey> = emptyList()

    // ---- 상수 ----
    private val MIC_DISTANCE_MAX_MM = 200f     // 최대 기준 거리 (mm)
    private val CURRENT_MIC_DIST_MM = 20f      // TODO: Orientation 기반 자동 갱신
    private val MAX_DRAW_RADIUS_MM = 500f      // 캔버스 내 최대 표현 거리 (mm)
    private val DEG_SPREAD = 60.0              // 시각화 각도 범위

    // ---- 스타일 ----
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

    // ---- 색상 팔레트 ----
    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,165,0), Color.YELLOW,
        Color.GREEN, Color.BLUE, Color.rgb(0,0,139), Color.rgb(138,43,226)
    )

    // ---- 외부 호출 ----
    fun updateSpeakers(speakers: List<SpeakerSignal>, voiceKeys: List<VoiceKey>) {
        this.speakers = speakers
        this.allVoiceKeys = voiceKeys
        invalidate()
    }

    // ----------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseY = h * 0.15f

        // --- 거리 스케일 계산 (절대 거리 기반) ---
        val scale = (h * 0.65f) / MAX_DRAW_RADIUS_MM

        // --- 마이크 간격 동적 계산 ---
        val micOffset = (CURRENT_MIC_DIST_MM * scale) / 2f

        // --- 마이크 표시 ---
        canvas.drawCircle(cx - micOffset, baseY, 20f, micPaint)
        canvas.drawText("L", cx - micOffset - 12f, baseY + 60f, textPaint)
        canvas.drawCircle(cx + micOffset, baseY, 20f, micPaint)
        canvas.drawText("R", cx + micOffset - 12f, baseY + 60f, textPaint)

        // --- 발성키 점 그리기 ---
        for ((i, key) in allVoiceKeys.withIndex()) {
            val colorIdx = i % colors.size
            val baseColor = colors[colorIdx]
            val alpha = (min(1.0, key.energy / 80.0) * 255).toInt().coerceIn(40, 255)
            dotPaint.color = baseColor
            dotPaint.alpha = alpha

            val d = key.distanceMm.toFloat().coerceAtMost(MAX_DRAW_RADIUS_MM) * scale
            val angle = ((key.deltaIndex.coerceIn(-100, 100) / 100f) * DEG_SPREAD).toFloat()
            val rad = Math.toRadians(angle.toDouble())
            val x = cx + sin(rad).toFloat() * d
            val y = baseY + cos(rad).toFloat() * d * 2f
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // --- 화자 원 표시 ---
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

            // ---- 거리 표시 (mm 단위) ----
            val txt = String.format("%.1fmm", spk.distance)
            textPaint.color = color
            textPaint.alpha = 230
            canvas.drawText(txt, x - 40f, y + 40f, textPaint)
        }
    }
}

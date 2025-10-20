package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import ai.willkim.wkwhisperkey.audio.SpeakerSignal
import kotlin.math.*

class WkSpeakerMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintMic = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 3f
    }
    private val paintSpeaker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var speakers: List<SpeakerSignal> = emptyList()
    private var maxDistance = 0.1

    fun updateSpeakers(list: List<SpeakerSignal>) {
        speakers = list
        maxDistance = (list.maxOfOrNull { it.distance } ?: 0.1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val topY = h * 0.15f
        val micGap = w * 0.25f

        // 마이크 두 개
        c.drawCircle(cx - micGap, topY, 20f, paintMic)
        c.drawCircle(cx + micGap, topY, 20f, paintMic)
        c.drawLine(cx - micGap, topY, cx + micGap, topY, paintLine)

        // 화자 표시
        for (spk in speakers) {
            val distRatio = (spk.distance / maxDistance).coerceIn(0.0, 1.0)
            val angleRad = spk.deltaIndex / 100.0 * Math.PI / 3.0 // ±60도 가정
            val len = (h * 0.6 * distRatio).toFloat()
            val x = (cx + sin(angleRad) * len).toFloat()
            val y = (topY + cos(angleRad) * len).toFloat()

            val eNorm = ((spk.energy - 40) / 40.0).coerceIn(0.0, 1.0)
            val red = (255 * eNorm).toInt()
            val blue = (255 * (1 - eNorm)).toInt()
            paintSpeaker.color = Color.rgb(red, 50, blue)

            c.drawLine(cx, topY, x, y, paintLine)
            c.drawCircle(x, y, 16f, paintSpeaker)
        }
    }
}

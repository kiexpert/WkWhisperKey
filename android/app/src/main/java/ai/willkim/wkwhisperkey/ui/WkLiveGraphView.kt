package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class WkLiveGraphView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paintLeft = Paint().apply {
        color = Color.CYAN
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintRight = Paint().apply {
        color = Color.MAGENTA
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintAxis = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val maxPoints = 400
    private val leftBuf = FloatArray(maxPoints) { 0f }
    private val rightBuf = FloatArray(maxPoints) { 0f }
    private var idx = 0
    private var filled = false

    fun onFrame(lDb: Double, rDb: Double) {
        val normL = ((lDb / 120.0).coerceIn(0.0, 1.0) * height).toFloat()
        val normR = ((rDb / 120.0).coerceIn(0.0, 1.0) * height).toFloat()
        leftBuf[idx] = normL
        rightBuf[idx] = normR
        idx = (idx + 1) % maxPoints
        if (idx == 0) filled = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val start = if (filled) idx else 0
        val count = if (filled) maxPoints else idx

        // 중앙 기준선
        canvas.drawLine(0f, h - h / 2f, w.toFloat(), h - h / 2f, paintAxis)

        // 좌·우 그래프
        for (i in 1 until count) {
            val x1 = (i - 1).toFloat()
            val x2 = i.toFloat()
            val li = (start + i) % maxPoints
            val prev = (li - 1 + maxPoints) % maxPoints
            val y1L = h - leftBuf[prev]
            val y2L = h - leftBuf[li]
            val y1R = h - rightBuf[prev]
            val y2R = h - rightBuf[li]
            canvas.drawLine(x1, y1L, x2, y2L, paintLeft)
            canvas.drawLine(x1, y1R, x2, y2R, paintRight)
        }
    }
}

package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class WkPhaseScatterView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val maxFrames = 400       // 프레임 버퍼 (세로 픽셀 수)
    private val bands = 8
    private val phases = Array(maxFrames) { FloatArray(bands) }
    private val amps = Array(maxFrames) { FloatArray(bands) }
    private var frameIndex = 0
    private var filled = false

    // 빨주노초파남보 + 검(성대)
    private val colors = intArrayOf(
        Color.BLACK, Color.RED, Color.rgb(255,128,0),
        Color.YELLOW, Color.GREEN, Color.CYAN,
        Color.BLUE, Color.rgb(128,0,255)
    )

    private val paints = colors.map {
        Paint().apply {
            color = it
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    /** Δφ (우–좌) 와 강도(dB) 업데이트 */
    fun onFrame(phaseDeg: DoubleArray, magDb: DoubleArray) {
        val row = frameIndex
        for (k in 0 until bands) {
            phases[row][k] = phaseDeg[k].toFloat()
            amps[row][k] = magDb[k].toFloat()
        }
        frameIndex = (frameIndex + 1) % maxFrames
        if (frameIndex == 0) filled = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        //fun mapX(phi: Float) = (w / 2f) + (phi / 180f) * (w / 2f)
        fun mapX(phi: Float) = (w / 2f) - (phi / 180f) * (w / 2f)

        val frames = if (filled) maxFrames else frameIndex
        val start = if (filled) frameIndex else 0

        for (i in 0 until frames) {
            val row = (start + i) % maxFrames
            val y = h - (i.toFloat() / frames) * h  // 아래→위 스크롤

            for (k in 0 until bands) {
                val phi = phases[row][k]
                val db = amps[row][k]
                val alpha = ((db / 120f) * 255).roundToInt().coerceIn(20, 255)
                val paint = paints[k]
                paint.alpha = alpha
                val x = mapX(phi)
                canvas.drawCircle(x, y, 3f, paint)
            }
        }

        // 중앙선 (Δφ = 0°)
        val axis = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 1f
        }
        canvas.drawLine(w / 2f, 0f, w / 2f, h, axis)
    }
}

package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class WkPhaseScatterView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val maxFrames = 400     // 세로 버퍼 (한 프레임당 1px)
    private val bands = 8
    private val points = Array(maxFrames) { FloatArray(bands) }   // Δφ
    private val amps = Array(maxFrames) { FloatArray(bands) }     // dB (강도)
    private var frameIndex = 0
    private var filled = false

    private val paints = arrayOf(
        Paint().apply { color = Color.RED },
        Paint().apply { color = Color.rgb(255, 128, 0) },
        Paint().apply { color = Color.YELLOW },
        Paint().apply { color = Color.GREEN },
        Paint().apply { color = Color.CYAN },
        Paint().apply { color = Color.BLUE },
        Paint().apply { color = Color.rgb(128, 0, 255) },
        Paint().apply { color = Color.MAGENTA }
    )

    fun onFrame(phaseDeg: DoubleArray, magDb: DoubleArray) {
        val row = frameIndex
        for (k in 0 until bands) {
            points[row][k] = phaseDeg[k].toFloat()
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

        // 좌표 변환: 위상(-180~+180) → y 위치
        fun mapY(phi: Float) = (h / 2f) - (phi / 180f) * (h / 2f)

        val frames = if (filled) maxFrames else frameIndex
        val start = if (filled) frameIndex else 0

        for (i in 0 until frames) {
            val row = (start + i) % maxFrames
            val x = w - (i.toFloat() / frames) * w  // 오른쪽→왼쪽 스크롤

            for (k in 0 until bands) {
                val phi = points[row][k]
                val db = amps[row][k]
                val alpha = ((db / 120f) * 255).roundToInt().coerceIn(20, 255)
                val paint = paints[k]
                paint.alpha = alpha
                val y = mapY(phi)
                canvas.drawCircle(x, y, 3f, paint)
            }
        }

        // 중앙선
        val axis = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 1f
        }
        canvas.drawLine(0f, h / 2f, w, h / 2f, axis)
    }
}

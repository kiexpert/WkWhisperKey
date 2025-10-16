package ai.willkim.wkwhisperkey.ui

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.max

/**
 * WkTrainerAnalyticsActivity v1.0
 * -------------------------------
 * /sdcard/WkWhisperLogs 아래 로그의 변화를 시계열로 시각화.
 * - 하루별 교정 패턴 개수
 * - 평균 신뢰도(confidence)
 */
class WkTrainerAnalyticsActivity : Activity() {

    private lateinit var chart: AnalyticsChart
    private val logDir = File("/sdcard/WkWhisperLogs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(layout)
        setContentView(scroll)

        layout.addView(TextView(this).apply {
            text = "📊 Whisper 학습 로그 분석"
            textSize = 20f
        })

        chart = AnalyticsChart(this)
        layout.addView(chart)
        chart.loadFrom(logDir)
    }
}

/** Canvas 기반 간단한 선 그래프 */
class AnalyticsChart(context: Activity) : View(context) {
    private val paintLine = Paint().apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintConf = Paint().apply {
        color = Color.MAGENTA
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 26f
    }
    private var patternCounts = listOf<Int>()
    private var confValues = listOf<Float>()

    fun loadFrom(dir: File) {
        val files = dir.listFiles()?.filter { it.extension == "txt" }?.sortedBy { it.lastModified() } ?: return
        val counts = mutableListOf<Int>()
        val confs = mutableListOf<Float>()
        for (f in files.takeLast(30)) {
            val lines = f.readLines()
            val c = lines.count { it.startsWith("JNI=") }
            val confLine = lines.find { it.startsWith("Confidence=") }
            val conf = confLine?.substringAfter("=")?.toFloatOrNull() ?: 0f
            counts += c
            confs += conf
        }
        patternCounts = counts
        confValues = confs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (patternCounts.isEmpty()) {
            canvas.drawText("로그 없음", 50f, 100f, paintText)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val n = patternCounts.size
        val xStep = w / max(1, n - 1)
        val maxCount = max(1, patternCounts.maxOrNull() ?: 1)

        val pathCount = Path()
        for (i in patternCounts.indices) {
            val x = i * xStep
            val y = h - (patternCounts[i] / maxCount.toFloat()) * h * 0.8f
            if (i == 0) pathCount.moveTo(x, y) else pathCount.lineTo(x, y)
        }
        canvas.drawPath(pathCount, paintLine)
        canvas.drawText("패턴 수", 20f, 40f, paintText)

        val pathConf = Path()
        for (i in confValues.indices) {
            val x = i * xStep
            val y = h - confValues[i].coerceIn(0f, 1f) * h * 0.8f
            if (i == 0) pathConf.moveTo(x, y) else pathConf.lineTo(x, y)
        }
        canvas.drawPath(pathConf, paintConf)
        canvas.drawText("신뢰도", 20f, 80f, paintText)
    }
}

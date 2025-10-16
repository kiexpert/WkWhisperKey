package ai.willkim.wkwhisperkey.ui

import ai.willkim.wkwhisperkey.whisper.core.WkWhisperTrainer
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import kotlin.math.min

/**
 * WkTrainerHUDActivity v1.1
 * -------------------------
 * Whisper 교정 사전(corrections.txt) 기반 학습 상태 패널.
 * - [재학습] 버튼
 * - [📈 추세 보기] 버튼 추가 (AnalyticsActivity로 이동)
 */
class WkTrainerHUDActivity : Activity() {

    private lateinit var layout: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var btnRetrain: Button
    private lateinit var btnAnalytics: Button
    private lateinit var progress: ProgressBar
    private lateinit var txtStatus: TextView
    private val logDir = File("/sdcard/WkWhisperLogs")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        setContentView(layout)

        txtStatus = TextView(this).apply {
            text = "교정 사전 상태 로드 중..."
            textSize = 16f
        }
        layout.addView(txtStatus)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        layout.addView(progress)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        btnRetrain = Button(this).apply {
            text = "🔁 재학습"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { retrain() }
        }

        btnAnalytics = Button(this).apply {
            text = "📈 추세 보기"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                startActivity(Intent(this@WkTrainerHUDActivity, WkTrainerAnalyticsActivity::class.java))
            }
        }

        btnRow.addView(btnRetrain)
        btnRow.addView(btnAnalytics)
        layout.addView(btnRow)

        layout.addView(TextView(this).apply {
            text = "\n📘 교정 패턴 목록"
            textSize = 18f
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(list)
        layout.addView(scroll)

        loadCorrections()
    }

    private fun loadCorrections() {
        val file = File(logDir, "corrections.txt")
        if (!file.exists()) {
            txtStatus.text = "교정 데이터가 없습니다."
            return
        }

        val lines = file.readLines()
        val total = lines.size
        list.removeAllViews()
        lines.forEachIndexed { i, line ->
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (min(100, 10 + i)).toInt() * 5, 8
                )
                setBackgroundColor(Color.rgb(100, 200, 255 - (i % 100)))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = line })
                addView(bar)
                setPadding(0, 4, 0, 4)
            }
            list.addView(row)
        }

        txtStatus.text = "총 ${total}개 교정 패턴 로드 완료"
        progress.progress = min(100, total)
    }

    private fun retrain() {
        Toast.makeText(this, "재학습 실행 중...", Toast.LENGTH_SHORT).show()
        Thread {
            WkWhisperTrainer.trainFromLogs()
            runOnUiThread {
                loadCorrections()
                Toast.makeText(this, "재학습 완료", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}

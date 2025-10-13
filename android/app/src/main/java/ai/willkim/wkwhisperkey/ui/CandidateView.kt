package ai.willkim.wkwhisperkey

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView

class CandidateView(context: Context) : LinearLayout(context) {

    var onSentenceClicked: (() -> Unit)? = null
    var onEnterLongPressed: (() -> Unit)? = null
    private val hintText = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        hintText.textSize = 16f
        addView(hintText)
        setPadding(16, 8, 16, 8)
        setBackgroundColor(0x11000000)
    }

    fun showHint(msg: String) { hintText.text = msg }

    fun showRecognized(sentence: String) {
        removeAllViews()
        val textView = TextView(context).apply {
            text = sentence
            textSize = 18f
            setOnClickListener { onSentenceClicked?.invoke() }
        }
        val enterBtn = TextView(context).apply {
            text = "⏎"
            textSize = 22f
            setPadding(24, 0, 0, 0)
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> pressStart = System.currentTimeMillis()
                    MotionEvent.ACTION_UP -> {
                        val dur = System.currentTimeMillis() - pressStart
                        if (dur > 400) onEnterLongPressed?.invoke()
                        else onSentenceClicked?.invoke()
                    }
                }
                true
            }
        }
        addView(textView)
        addView(enterBtn)
    }

    private var pressStart = 0L
}

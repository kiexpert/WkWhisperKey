package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.*

@Composable
fun WhisperVisualizer(speakers: List<SpeakerData>) {
    Canvas(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 3

        for (s in speakers) {
            val pos = Offset(
                center.x + cos(s.angle) * radius,
                center.y + sin(s.angle) * radius
            )
            val len = 60f + s.energy * 200f

            drawLine(
                color = s.color,
                start = center,
                end = pos.copy(y = pos.y - len / 2),
                strokeWidth = 6f
            )
            drawCircle(color = s.color, radius = 14f, center = pos)
            drawIntoCanvas {
                it.nativeCanvas.drawText(
                    "ID ${s.id}",
                    pos.x - 20f,
                    pos.y + 40f,
                    Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                    }
                )
            }
        }
    }
}

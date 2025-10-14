package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp

@Composable
fun WhisperVisualizer(
    energyMap: List<Float> = listOf(0.2f, 0.5f, 0.9f),
    speakers: List<String> = listOf("A", "B", "C")
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                isAntiAlias = true
            }
            speakers.forEachIndexed { i, s ->
                val x = 80f
                val y = 100f + i * 120f
                val width = energyMap.getOrNull(i)?.times(600f) ?: 0f
                drawRect(
                    color = Color.Green.copy(alpha = 0.4f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(width, 60f)
                )
                canvas.nativeCanvas.drawText("Speaker $s", x, y - 10f, paint)
            }
        }
    }
}

package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // ✅ 진한 다크 배경
    ) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.CYAN // ✅ 밝은 대비 색상
                textSize = 42f
                isAntiAlias = true
            }

            speakers.forEachIndexed { i, s ->
                val x = 80f
                val y = 150f + i * 140f
                val energy = energyMap.getOrNull(i)?.coerceIn(0f, 1f) ?: 0f
                val width = 800f * energy

                // ✅ 에너지 막대
                drawRect(
                    color = Color.Green.copy(alpha = 0.5f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(width, 70f)
                )

                // ✅ 텍스트
                canvas.nativeCanvas.drawText("Speaker $s: %.2f".format(energy), x, y - 10f, paint)
            }
        }
    }
}

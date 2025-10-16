package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import ai.willkim.wkwhisperkey.viewmodel.WhisperVisualizerViewModel

@Composable
fun WhisperVisualizer(viewModel: WhisperVisualizerViewModel) {
    val speakers = viewModel.speakers.collectAsState().value

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // ✅ 다크 배경
    ) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.CYAN
                textSize = 42f
                isAntiAlias = true
            }

            speakers.forEachIndexed { i, s ->
                val x = 80f
                val y = 150f + i * 140f
                val energy = s.energy.coerceIn(0f, 1f)
                val width = 800f * energy

                // ✅ 막대 (화자별 고유 색상)
                drawRect(
                    color = s.color.copy(alpha = 0.6f),
                    topLeft = androidx.compose.ui.geometry.Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(width, 70f)
                )

                // ✅ 텍스트 (화자 ID와 에너지)
                canvas.nativeCanvas.drawText(
                    "Speaker ${s.id} (${s.angle.toInt()}°): %.2f".format(energy),
                    x,
                    y - 10f,
                    paint
                )
            }

            if (speakers.isEmpty()) {
                canvas.nativeCanvas.drawText(
                    "No active input",
                    100f,
                    200f,
                    paint.apply { color = android.graphics.Color.GRAY }
                )
            }
        }
    }
}

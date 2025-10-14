package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogVisualizerOverlay(
    modifier: Modifier = Modifier
) {
    val logs by WkLog.logs.collectAsState()
    val scrollState = rememberScrollState()

    var selected by remember { mutableStateOf("ALL") }
    val filters = listOf("ALL", "SYSTEM", "AUDIO", "NETWORK", "CRASH")

    val filtered = remember(logs, selected) {
        if (selected == "ALL") logs
        else logs.filter { it.contains(selected, ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {

        // --- Whisper Visualizer background layer ---
        WhisperVisualizer(modifier = Modifier.fillMaxSize())

        // --- Logs overlay ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .alpha(0.9f)
        ) {
            // --- Filter bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202020).copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { key ->
                    val isSel = selected == key
                    Text(
                        text = when (key) {
                            "SYSTEM" -> "⚙ SYSTEM"
                            "AUDIO" -> "🎙 AUDIO"
                            "NETWORK" -> "🌐 NETWORK"
                            "CRASH" -> "💥 CRASH"
                            else -> "🪶 ALL"
                        },
                        color = if (isSel) Color(0xFF00FF88) else Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { selected = key }
                            .padding(horizontal = 4.dp)
                    )
                }
            }

            // --- Log text area ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(4.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    filtered.forEachIndexed { idx, line ->
                        Text(
                            text = "${idx + 1}: $line",
                            color = when {
                                line.contains("E/") -> Color(0xFFFF6666)
                                line.contains("I/") -> Color(0xFFB0E0FF)
                                else -> Color(0xFFEEEEEE)
                            },
                            fontSize = 11.sp,
                            softWrap = true,
                            overflow = TextOverflow.Clip,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }

        // --- Right Scrollbar ---
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(4.dp),
            adapter = rememberScrollbarAdapter(scrollState),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = Color.Gray.copy(alpha = 0.3f),
                hoverColor = Color.White.copy(alpha = 0.8f)
            )
        )
    }
}

package ai.willkim.wkwhisperkey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.willkim.wkwhisperkey.WkLog

@Composable
fun LogVisualizerOverlay(modifier: Modifier = Modifier) {
    val logs by WkLog.logs.collectAsState()
    val scroll = rememberScrollState()

    var selected by remember { mutableStateOf("ALL") }
    val filters = listOf("ALL", "SYSTEM", "AUDIO", "NETWORK", "CRASH")
    val filtered = remember(logs, selected) {
        if (selected == "ALL") logs
        else logs.filter { it.contains(selected, ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // (배경: WhisperVisualizer)
        WhisperVisualizer(emptyList())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .verticalScroll(scroll)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
                    .padding(6.dp),
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
                        lineHeight = 14.sp,
                        modifier = Modifier.clickable { selected = key }
                    )
                }
            }

            filtered.forEachIndexed { idx, line ->
                Text(
                    text = "${idx + 1}: $line",
                    color = when {
                        line.startsWith("E/") -> Color(0xFFFF6666)
                        line.startsWith("I/") -> Color(0xFFB0E0FF)
                        else -> Color(0xFFEEEEEE)
                    },
                    fontSize = 11.sp,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

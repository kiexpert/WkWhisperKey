package ai.willkim.wkwhisperkey.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.willkim.wkwhisperkey.WkLog
import ai.willkim.wkwhisperkey.viewmodel.WhisperVisualizerViewModel
import kotlinx.coroutines.launch

// ------------------------------------------------------------
// 🎙 상단 마이크 상태 표시 및 Reconnect 버튼
// ------------------------------------------------------------
@Composable
fun MicStatusPanel(
    context: Context,
    viewModel: WhisperVisualizerViewModel
) {
    val micList = remember { mutableStateListOf<String>() }
    var isRecording by remember { mutableStateOf(false) }

    // 마이크 목록 초기화
    LaunchedEffect(Unit) {
        micList.clear()
        micList.addAll(getAvailableMics(context))
        isRecording = true
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "🎙 Active Mics: ${if (micList.isEmpty()) "None" else micList.joinToString()}",
                color = Color(0xFF00FFAA),
                fontSize = 13.sp
            )
            Text(
                text = if (isRecording) "Status: Recording" else "Status: Idle",
                color = if (isRecording) Color.Green else Color.Red,
                fontSize = 12.sp
            )
        }

        Button(
            onClick = {
                isRecording = false
                viewModel.reconnectAudio()
                isRecording = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2222AA)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text("🔄 Reconnect", fontSize = 12.sp, color = Color.White)
        }
    }
}

// 🎧 마이크 목록 가져오기
fun getAvailableMics(context: Context): List<String> {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return devices.mapNotNull { d ->
        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_DEVICE
        ) {
            d.productName?.toString()
        } else null
    }
}

// ------------------------------------------------------------
// 🧠 로그 + 비주얼라이저 오버레이 통합
// ------------------------------------------------------------
@Composable
fun LogVisualizerOverlay(
    context: Context,
    viewModel: WhisperVisualizerViewModel,
    modifier: Modifier = Modifier
) {
    val logs by WkLog.logs.collectAsState()
    val scroll = rememberScrollState()
    val coroutine = rememberCoroutineScope()

    var selected by remember { mutableStateOf("ALL") }
    val filters = listOf("ALL", "SYSTEM", "AUDIO", "NETWORK", "CRASH")
    val filtered = remember(logs, selected) {
        if (selected == "ALL") logs
        else logs.filter { it.contains(selected, ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 배경: Whisper Visualizer
        WhisperVisualizer(viewModel)

        // 로그 오버레이
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .verticalScroll(scroll)
        ) {
            // 마이크 상태 패널
            MicStatusPanel(context, viewModel)

            // 필터 버튼 행
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
                        modifier = Modifier.clickable {
                            selected = key
                            coroutine.launch { scroll.scrollTo(0) }
                        }
                    )
                }
            }

            // 로그 리스트
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

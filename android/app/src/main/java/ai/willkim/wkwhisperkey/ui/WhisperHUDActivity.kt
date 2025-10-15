package ai.willkim.wkwhisperkey.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ai.willkim.wkwhisperkey.ui.WhisperVisualizerViewModel

class WhisperHUDActivity : ComponentActivity() {

    private val viewModel: WhisperVisualizerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // ✅ 배경: Whisper 비주얼라이저
                    WhisperVisualizer(viewModel)

                    // ✅ 오버레이: 로그 뷰어 (투명도 높게)
                    LogVisualizerOverlay(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x66000000)) // 반투명 블랙
                    )
                }
            }
        }
    }
}

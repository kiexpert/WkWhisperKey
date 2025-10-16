package ai.willkim.wkwhisperkey.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ai.willkim.wkwhisperkey.viewmodel.WhisperVisualizerViewModel

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
                    // ✅ 2. 로그 및 마이크 상태 오버레이
                    LogVisualizerOverlay(
                        context = this@WhisperHUDActivity,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x66000000)) // 반투명 블랙 오버레이
                    )

                    // ✅ 1. Whisper 비주얼라이저 (배경)
                    WhisperVisualizer(viewModel)
                }
            }
        }
    }
}

package ai.willkim.wkwhisperkey.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.willkim.wkwhisperkey.viewmodel.WhisperViewModel

class WhisperHUDActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: WhisperViewModel = viewModel()
                val uiState by vm.state.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF9F6FF) // 은은한 라벤더 톤
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(text = "Detected Speaker: ${uiState.currentSpeaker}")
                        Spacer(Modifier.height(8.dp))
                        Text(text = "Energy: %.2f".format(uiState.energy))
                    }
                }
            }
        }
    }
}

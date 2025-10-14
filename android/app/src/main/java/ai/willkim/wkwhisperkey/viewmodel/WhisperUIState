package ai.willkim.wkwhisperkey.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WhisperUIState(
    val currentSpeaker: String = "—",
    val energy: Float = 0f
)

class WhisperViewModel : ViewModel() {
    private val _state = MutableStateFlow(WhisperUIState())
    val state: StateFlow<WhisperUIState> = _state
}

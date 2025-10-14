package ai.willkim.wkwhisperkey.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.*
import androidx.compose.ui.graphics.Color

data class SpeakerData(
    val id: Int,
    val energy: Float,
    val angle: Float,
    val color: Color
)

class WhisperVisualizerViewModel : ViewModel() {
    private val _speakers = MutableStateFlow<List<SpeakerData>>(emptyList())
    val speakers: StateFlow<List<SpeakerData>> = _speakers

    fun updateSpeaker(id: Int, audio: FloatArray, angle: Float) {
        viewModelScope.launch {
            val energy = computeEnergy(audio)
            val color = when (id % 3) {
                0 -> Color(0xFF00FF88)
                1 -> Color(0xFF4488FF)
                else -> Color(0xFFFF4444)
            }
            val updated = _speakers.value.toMutableList()
            updated.removeAll { it.id == id }
            updated.add(SpeakerData(id, energy, angle, color))
            _speakers.value = updated
        }
    }

    private fun computeEnergy(a: FloatArray): Float {
        var e = 0f
        for (v in a) e += v * v
        return sqrt(e / a.size)
    }

    init {
        viewModelScope.launch {
            var tick = 0
            while (true) {
                // 가짜 화자 데이터 (시각적 테스트용)
                val fakeSpeakers = List(3) {
                    SpeakerData(
                        id = it,
                        energy = ((sin(tick / 10f + it) + 1f) / 2f).coerceIn(0f, 1f),
                        angle = (it * 120f + tick) % 360f,
                        color = when (it) {
                            0 -> Color(0xFF00FF88)
                            1 -> Color(0xFF4488FF)
                            else -> Color(0xFFFF4444)
                        }
                    )
                }
                _speakers.value = fakeSpeakers
                tick++
                kotlinx.coroutines.delay(100)
            }
        }
    }
}

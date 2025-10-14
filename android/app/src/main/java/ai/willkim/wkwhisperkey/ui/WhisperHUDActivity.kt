package ai.willkim.wkwhisperkey.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ai.willkim.wkwhisperkey.audio.WkAudioInput
import ai.willkim.wkwhisperkey.core.WkIntentRouter
import ai.willkim.wkwhisperkey.core.WkSpatialSeparator
import kotlinx.coroutines.*
import kotlin.math.*

class WhisperHUDActivity : ComponentActivity() {
    private val vm by viewModels<WhisperVisualizerViewModel>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WhisperVisualizer(vm.speakers.collectAsState().value) }

        WkAudioInput.start()
        scope.launch {
            WkAudioInput.channelFlow.collect { mono ->
                val (l, c, r) = simulate3ch(mono)
                val id = WkSpatialSeparator.identifySpeaker(l, c, r)
                WkIntentRouter.routeAudio(id, l, c, r)
                val separated = WkIntentRouter.getSpeakerAudio(id)
                val angle = when (id % 3) { 0 -> -45f; 1 -> 0f; else -> 45f } *
                    (PI.toFloat() / 180f)
                vm.updateSpeaker(id, separated, angle)
            }
        }
    }

    private fun simulate3ch(a: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
        val d = 8
        val l = FloatArray(a.size) { i -> if (i >= d) a[i - d] else 0f }
        val c = a.copyOf()
        val r = FloatArray(a.size) { i -> if (i + d < a.size) a[i + d] else 0f }
        return Triple(l, c, r)
    }

    override fun onDestroy() {
        WkAudioInput.stop()
        scope.cancel()
        super.onDestroy()
    }
}

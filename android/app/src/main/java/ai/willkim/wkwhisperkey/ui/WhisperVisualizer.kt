package ai.willkim.wkwhisperkey.ui

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
fun WhisperVisualizer() {
    val raw = remember { mutableStateListOf<Float>() }
    val filtered = remember { mutableStateListOf<Float>() }
    val fft = remember { mutableStateListOf<Float>() }

    // 오디오 캡처 루프
    LaunchedEffect(Unit) {
        val sr = 16000
        val bufSize = AudioRecord.getMinBufferSize(sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize)

        val buf = ShortArray(bufSize)
        rec.startRecording()
        while (isActive) {
            val n = rec.read(buf, 0, buf.size)
            if (n > 0) {
                val frame = FloatArray(n) { i -> buf[i] / 32768f }
                raw.clear(); raw.addAll(frame.toList())
                val f = imfHighpass(frame)
                filtered.clear(); filtered.addAll(f.toList())
                fft.clear(); fft.addAll(fftMag(f).toList())
            }
        }
        rec.stop()
        rec.release()
    }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        TextScope("Raw(빨강) / Filtered(초록)")
        WaveView(raw, filtered)
        Spacer(Modifier.height(8.dp))
        TextScope("FFT Spectrum")
        FftView(fft)
    }
}

@Composable
private fun TextScope(text: String) {
    androidx.compose.material3.Text(text, color = Color.Gray)
}

// 파형 뷰
@Composable
private fun WaveView(raw: List<Float>, filtered: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val midY = size.height / 2f
        val scaleX = size.width / max(raw.size, 1)
        for (i in raw.indices) {
            val x = i * scaleX
            drawLine(Color.Red, Offset(x, midY), Offset(x, midY - raw[i] * 120))
        }
        for (i in filtered.indices) {
            val x = i * scaleX
            drawLine(Color.Green, Offset(x, midY), Offset(x, midY - filtered[i] * 120))
        }
    }
}

// FFT 스펙트럼 뷰
@Composable
private fun FftView(fft: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        if (fft.isEmpty()) return@Canvas
        val maxVal = fft.max()
        val scaleX = size.width / fft.size
        val scaleY = size.height / maxVal
        for (i in fft.indices) {
            val x = i * scaleX
            drawLine(Color.Cyan, Offset(x, size.height),
                Offset(x, size.height - fft[i] * scaleY))
        }
    }
}

// 간단 IMF + 하이패스 필터
private fun imfHighpass(x: FloatArray): FloatArray {
    val y = FloatArray(x.size)
    var prev = x[0]
    for (i in 1 until x.size) {
        val tmp = x[i]
        y[i] = x[i] - 0.97f * prev
        prev = tmp
    }
    return y
}

// FFT magnitude
private fun fftMag(data: FloatArray): FloatArray {
    val n = data.size
    val out = FloatArray(n / 2)
    for (k in 0 until n / 2) {
        var re = 0f; var im = 0f
        for (t in 0 until n) {
            val ang = -2f * PI.toFloat() * k * t / n
            re += data[t] * cos(ang)
            im += data[t] * sin(ang)
        }
        out[k] = sqrt(re * re + im * im)
    }
    return out
}

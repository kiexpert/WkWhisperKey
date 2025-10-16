package ai.willkim.wkwhisperkey.whisper.api

import ai.willkim.wkwhisperkey.whisper.WhisperEngineInterface
import android.util.Log
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * WhisperApiEngine
 * ----------------
 * OpenAI Whisper API 호출용 엔진.
 * - PCM을 WAV 파일로 저장 후 업로드
 */
class WhisperApiEngine(private val apiKey: String) : WhisperEngineInterface {

    override fun transcribe(pcmBuffer: ByteBuffer): String? {
        val tmpFile = File.createTempFile("whisper_", ".wav")
        writeWav(tmpFile, pcmBuffer)
        val result = callApi(tmpFile)
        tmpFile.delete()
        return result
    }

    private fun callApi(file: File): String? {
        val url = URL("https://api.openai.com/v1/audio/transcriptions")
        val boundary = "WhisperForm${System.currentTimeMillis()}"
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
        }

        DataOutputStream(conn.outputStream).use { out ->
            fun writeLine(s: String) = out.writeBytes("$s\r\n")

            writeLine("--$boundary")
            writeLine("Content-Disposition: form-data; name=\"model\"")
            writeLine("")
            writeLine("whisper-1")

            writeLine("--$boundary")
            writeLine("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"")
            writeLine("Content-Type: audio/wav")
            writeLine("")
            file.inputStream().use { it.copyTo(out) }
            writeLine("")
            writeLine("--$boundary--")
        }

        return try {
            conn.inputStream.bufferedReader().readText().also {
                Log.i("WhisperAPI", "API result len=${it.length}")
            }
        } catch (e: Exception) {
            Log.e("WhisperAPI", "error: ${e.message}")
            conn.errorStream?.bufferedReader()?.readText()
        }
    }

    /** PCM → WAV 파일 변환 */
    private fun writeWav(file: File, pcm: ByteBuffer) {
        val out = DataOutputStream(FileOutputStream(file))
        val pcmData = ByteArray(pcm.remaining())
        pcm.get(pcmData)
        val sampleRate = 16000
        val byteRate = sampleRate * 2
        val dataLen = pcmData.size + 36
        // WAV 헤더
        out.writeBytes("RIFF")
        out.writeInt(Integer.reverseBytes(dataLen))
        out.writeBytes("WAVEfmt ")
        out.writeInt(Integer.reverseBytes(16))
        out.writeShort(java.lang.Short.reverseBytes(1))
        out.writeShort(java.lang.Short.reverseBytes(1))
        out.writeInt(Integer.reverseBytes(sampleRate))
        out.writeInt(Integer.reverseBytes(byteRate))
        out.writeShort(java.lang.Short.reverseBytes(2))
        out.writeShort(java.lang.Short.reverseBytes(16))
        out.writeBytes("data")
        out.writeInt(Integer.reverseBytes(pcmData.size))
        out.write(pcmData)
        out.close()
    }
}

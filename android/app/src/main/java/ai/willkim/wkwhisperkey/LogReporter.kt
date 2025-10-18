package ai.willkim.wkwhisperkey.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.InputStreamReader
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogReporter {
    private const val TAG = "WkLogReporter"
    private const val CHANNEL_ID = "wkwhisper_alerts"
    private const val CHANNEL_NAME = "WkWhisper Alerts"

    fun initChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    /** 앱 내에서 logcat을 덤프해 파일로 저장하고 토스트/노티로 알림 */
    fun dumpLogAndNotify(ctx: Context, reason: String) {
        try {
            // 파일 위치: 앱 외부전용 공용 폴더 (앱 제거 시 삭제됨)
            val dir = File(ctx.getExternalFilesDir(null), "WkLogs")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "log_$ts.txt")

            // logcat 명령: 앱 태그 중심 + 에러 레벨 추가
            // -d: dump and exit
            // 필터는 필요시 조절
            val cmd = arrayOf("logcat", "-d", "WkSafety:V", "WkNative:V", "MicArray:V", "*:E")
            val proc = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line).append("\n")
                line = reader.readLine()
            }
            reader.close()
            outFile.writeText("Reason: $reason\n\n")
            outFile.appendText(sb.toString())

            // 사용자 알림: Toast + Notification
            Toast.makeText(ctx, "로그 저장됨: ${outFile.name}", Toast.LENGTH_LONG).show()
            notify(ctx, "비정상 감지", "$reason — 로그 저장됨: ${outFile.name}", outFile.absolutePath)
            Log.i(TAG, "Log saved to ${outFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "dumpLogAndNotify failed: ${e.message}")
            Toast.makeText(ctx, "로그 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun notify(ctx: Context, title: String, text: String, filePath: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent().apply {
            // 필요하면 파일 열기 또는 앱 실행 Intent 연결
        }
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10000).toInt(), n)
    }
}

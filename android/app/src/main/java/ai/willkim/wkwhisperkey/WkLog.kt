package ai.willkim.wkwhisperkey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Global reactive log system.
 * Collectable by Compose UI.
 */
object WkLog {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> get() = _logs

    fun i(tag: String, msg: String) = add("I/$tag: $msg")
    fun e(tag: String, msg: String) = add("E/$tag: $msg")

    private fun add(line: String) {
        val updated = (_logs.value + line).takeLast(300)
        _logs.value = updated
        android.util.Log.d("WkLog", line)
    }
}

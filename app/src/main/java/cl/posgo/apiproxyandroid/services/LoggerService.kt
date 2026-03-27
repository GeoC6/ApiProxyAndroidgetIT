package cl.posgo.apiproxyandroid.services

import android.content.Context
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class LoggerService(private val context: Context) {

    private val TAG = "ApiProxyAndroid"
    private val realtimeBuffer = CopyOnWriteArrayList<LogEntry>()
    private val maxBufferSize = 500

    data class LogEntry(
        val id: String,
        val timestamp: String,
        val level: String,
        val message: String,
        val data: Any? = null
    )

    fun info(message: String, vararg args: Any?) {
        val formattedMessage = if (args.isNotEmpty()) String.format(message, *args) else message
        Log.i(TAG, formattedMessage)
        addToBuffer("info", formattedMessage)
    }

    fun success(message: String, vararg args: Any?) {
        val formattedMessage = if (args.isNotEmpty()) String.format(message, *args) else message
        Log.i(TAG, "✓ $formattedMessage")
        addToBuffer("success", formattedMessage)
    }

    fun warn(message: String, vararg args: Any?) {
        val formattedMessage = if (args.isNotEmpty()) String.format(message, *args) else message
        Log.w(TAG, formattedMessage)
        addToBuffer("warn", formattedMessage)
    }

    fun error(message: String, vararg args: Any?) {
        val formattedMessage = if (args.isNotEmpty()) String.format(message, *args) else message
        Log.e(TAG, formattedMessage)
        addToBuffer("error", formattedMessage)
    }

    private fun addToBuffer(level: String, message: String, data: Any? = null) {
        val entry = LogEntry(
            id = "${System.currentTimeMillis()}-${(0..9999).random()}",
            timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()),
            level = level,
            message = message,
            data = data
        )

        realtimeBuffer.add(entry)

        if (realtimeBuffer.size > maxBufferSize) {
            realtimeBuffer.removeAt(0)
        }
    }

    fun getRealtimeBuffer(): List<LogEntry> = realtimeBuffer.toList()

    fun getStats(): Map<String, Any> {
        return mapOf(
            "bufferSize" to realtimeBuffer.size,
            "maxBufferSize" to maxBufferSize
        )
    }
}
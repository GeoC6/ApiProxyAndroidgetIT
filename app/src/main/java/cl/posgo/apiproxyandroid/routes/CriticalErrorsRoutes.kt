package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.database.CriticalError
import cl.posgo.apiproxyandroid.services.LoggerService
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import java.text.SimpleDateFormat
import java.util.*

class CriticalErrorsRoutes(
    private val context: Context,
    private val logger: LoggerService
) {
    private val gson = Gson()
    private val criticalErrors = mutableListOf<CriticalError>()

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/critical-errors" && method == NanoHTTPD.Method.GET ->
                getErrors()
            uri.startsWith("/api/critical-errors/clear/") && method == NanoHTTPD.Method.POST ->
                clearError(uri)
            uri == "/api/critical-errors/clear-all" && method == NanoHTTPD.Method.POST ->
                clearAllErrors()
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    fun addCriticalError(message: String, type: String = "error", source: String = "unknown", transactionId: Long? = null): Long {
        val error = CriticalError(
            id = System.currentTimeMillis(),
            message = message,
            type = type,
            source = source,
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()),
            transactionId = transactionId
        )

        criticalErrors.add(0, error)

        if (criticalErrors.size > 10) {
            criticalErrors.removeAt(criticalErrors.size - 1)
        }

        logger.error("[CRITICAL] $message")

        return error.id
    }

    private fun getErrors(): NanoHTTPD.Response {
        return try {
            cleanOldErrors()

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "errors" to criticalErrors,
                    "count" to criticalErrors.size,
                    "timestamp" to System.currentTimeMillis()
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo errores críticos: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf(
                    "errors" to emptyList<Any>(),
                    "count" to 0
                ))
            )
        }
    }

    private fun clearError(uri: String): NanoHTTPD.Response {
        return try {
            val errorId = uri.substringAfterLast("/").toLongOrNull() ?: 0L
            val initialSize = criticalErrors.size

            criticalErrors.removeAll { it.id == errorId }

            val removed = initialSize > criticalErrors.size

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to removed,
                    "message" to if (removed) "Error eliminado" else "Error no encontrado",
                    "remaining_count" to criticalErrors.size
                ))
            )
        } catch (e: Exception) {
            logger.error("Error eliminando error crítico: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    private fun clearAllErrors(): NanoHTTPD.Response {
        return try {
            val clearedCount = criticalErrors.size
            criticalErrors.clear()

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "message" to "$clearedCount errores eliminados",
                    "cleared_count" to clearedCount
                ))
            )
        } catch (e: Exception) {
            logger.error("Error limpiando errores críticos: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "message" to e.message))
            )
        }
    }

    private fun cleanOldErrors() {
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
        criticalErrors.removeAll { error ->
            val errorTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .parse(error.timestamp)?.time ?: 0L
            errorTime < fiveMinutesAgo
        }
    }
}
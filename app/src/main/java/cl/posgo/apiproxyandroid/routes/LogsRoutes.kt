package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class LogsRoutes(
    private val context: Context,
    private val logger: LoggerService
) {
    private val gson = Gson()

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/logs/history" && method == NanoHTTPD.Method.GET ->
                getHistory(session)
            uri == "/api/logs/stats" && method == NanoHTTPD.Method.GET ->
                getStats()
            uri == "/api/logs/test" && method == NanoHTTPD.Method.POST ->
                testLog(session)
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun getHistory(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val params = session.parms
            val limit = params["limit"]?.toIntOrNull() ?: 100
            val buffer = logger.getRealtimeBuffer()
            val logs = buffer.takeLast(limit)

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "logs" to logs,
                    "total" to buffer.size,
                    "limit" to limit,
                    "stats" to logger.getStats()
                ))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }

    private fun getStats(): NanoHTTPD.Response {
        return try {
            val stats = logger.getStats()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(stats)
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }

    private fun testLog(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val body = session.getBodyAsMap()
            val level = body["level"] as? String ?: "info"
            val message = body["message"] as? String ?: "Mensaje de prueba"

            when (level) {
                "error" -> logger.error("$message (prueba desde API)")
                "warn" -> logger.warn("$message (prueba desde API)")
                "success" -> logger.success("$message (prueba desde API)")
                else -> logger.info("$message (prueba desde API)")
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "message" to "Log de prueba enviado",
                    "level" to level,
                    "content" to message
                ))
            )
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }
}
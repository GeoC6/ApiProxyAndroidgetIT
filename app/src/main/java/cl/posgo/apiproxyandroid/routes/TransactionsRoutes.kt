package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.LoggerService
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class TransactionsRoutes(
    private val context: Context,
    private val database: DatabaseManager,
    private val logger: LoggerService
) {
    private val gson = Gson()

    fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/transactions/status" && method == NanoHTTPD.Method.GET ->
                getStatus()
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun getStatus(): NanoHTTPD.Response {
        return try {
            val pending = database.getPendingTransactions()

            val transactions = pending.map { t ->
                mapOf(
                    "id" to t.id,
                    "status" to t.status,
                    "created_at" to t.createdAt,
                    "error_message" to t.errorMessage
                )
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "pending_transactions" to pending.size,
                    "transactions" to transactions
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo status: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("error" to e.message))
            )
        }
    }
}
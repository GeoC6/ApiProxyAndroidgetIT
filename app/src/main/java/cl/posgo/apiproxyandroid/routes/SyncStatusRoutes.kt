package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.LoggerService
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class SyncStatusRoutes(
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
            uri == "/api/sync-status" && method == NanoHTTPD.Method.GET ->
                getSyncStatus()
            uri == "/api/sync-status/debug" && method == NanoHTTPD.Method.GET ->
                getDebugInfo()
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun getSyncStatus(): NanoHTTPD.Response {
        return try {
            val pendingTransactions = database.getPendingTransactions()
            val pendingCount = pendingTransactions.size

            val transactionData = pendingTransactions.map { transaction ->
                val ageMinutes = (System.currentTimeMillis() / 1000 - transaction.createdAt) / 60
                mapOf(
                    "id" to transaction.id,
                    "created_at" to transaction.createdAt,
                    "folio_dte" to transaction.folioDte,
                    "error_message" to transaction.errorMessage,
                    "age_minutes" to ageMinutes
                )
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "pending_count" to pendingCount,
                    "is_processing" to false,
                    "pending_transactions" to transactionData,
                    "status" to if (pendingCount == 0) "synced" else "pending",
                    "api_status" to "online"
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo sync status: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf(
                    "pending_count" to 0,
                    "is_processing" to false,
                    "pending_transactions" to emptyList<Any>(),
                    "status" to "error",
                    "api_status" to "error"
                ))
            )
        }
    }

    private fun getDebugInfo(): NanoHTTPD.Response {
        return try {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "transactions" to emptyList<Any>(),
                    "total_count" to 0,
                    "by_status" to mapOf(
                        "pending" to 0,
                        "completed" to 0,
                        "failed" to 0
                    )
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
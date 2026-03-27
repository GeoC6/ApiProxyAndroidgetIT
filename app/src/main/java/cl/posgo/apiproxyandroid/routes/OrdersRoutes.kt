package cl.posgo.apiproxyandroid.routes

import android.content.Context
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD

class OrdersRoutes(
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
            uri == "/api/orders/display" && method == NanoHTTPD.Method.GET ->
                getOrdersDisplay(session)
            uri == "/api/orders/update-status" && method == NanoHTTPD.Method.POST ->
                updateOrderStatus(session)
            uri == "/api/orders/pos" && method == NanoHTTPD.Method.POST ->
                createPosOrder(session)
            uri == "/api/orders/stats" && method == NanoHTTPD.Method.GET ->
                getOrdersStats()
            uri == "/api/orders/history" && method == NanoHTTPD.Method.GET ->
                getOrdersHistory(session)
            uri == "/api/orders/delivered" && method == NanoHTTPD.Method.GET ->
                getDeliveredOrders(session)
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun getOrdersDisplay(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val params = session.parms
            val type = params["type"] ?: "all"

            logger.info("Solicitud de órdenes para display (tipo: $type)")

            val orders = database.getOrdersForDisplay()

            val filteredOrders = when (type) {
                "customer" -> orders.filter {
                    listOf("en_preparacion", "listo").contains(it["order_status"])
                }
                "employee" -> orders.filter {
                    listOf("pendiente", "en_preparacion", "listo").contains(it["order_status"])
                }
                else -> orders
            }

            val groupedOrders = mapOf(
                "pendiente" to filteredOrders.filter { it["order_status"] == "pendiente" },
                "en_preparacion" to filteredOrders.filter { it["order_status"] == "en_preparacion" },
                "listo" to filteredOrders.filter { it["order_status"] == "listo" }
            )

            val stats = mapOf(
                "total" to filteredOrders.size,
                "pendiente" to groupedOrders["pendiente"]!!.size,
                "en_preparacion" to groupedOrders["en_preparacion"]!!.size,
                "listo" to groupedOrders["listo"]!!.size
            )

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "type" to type,
                    "stats" to stats,
                    "orders" to groupedOrders,
                    "timestamp" to System.currentTimeMillis()
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo órdenes: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun updateOrderStatus(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val body = session.getBodyAsMap()
            val transactionId = (body["transaction_id"] as? Number)?.toInt() ?: 0
            val newStatus = body["new_status"] as? String ?: ""

            if (transactionId == 0 || newStatus.isEmpty()) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "Parámetros requeridos"))
                )
            }

            logger.info("Actualizando orden ID $transactionId a estado: $newStatus")

            val changes = database.updateOrderStatus(transactionId, newStatus)

            if (changes > 0) {
                logger.success("Orden ID $transactionId actualizada a: $newStatus")

                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    gson.toJson(mapOf(
                        "success" to true,
                        "message" to "Orden actualizada a $newStatus",
                        "transaction_id" to transactionId,
                        "new_status" to newStatus,
                        "updated_at" to System.currentTimeMillis()
                    ))
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "Orden no encontrada"))
                )
            }
        } catch (e: Exception) {
            logger.error("Error actualizando orden: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun createPosOrder(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val body = session.getBodyAsMap()
            val sessionId = (body["session_id"] as? Number)?.toInt() ?: 0
            val totalAmount = (body["total_amount"] as? Number)?.toDouble() ?: 0.0

            if (sessionId == 0 || totalAmount == 0.0) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    "application/json",
                    gson.toJson(mapOf("success" to false, "error" to "Parámetros requeridos"))
                )
            }

            logger.info("Nueva orden POS - Sesión: $sessionId, Total: $$totalAmount")

            val orderResult = database.saveTransactionWithOrder(
                data = body,
                sessionId = sessionId,
                source = "pos",
                terminalName = "POS-$sessionId",
                whatsappNumber = null,
                dteResponse = null
            )

            logger.success("Orden POS ${orderResult["orderNumber"]} creada")

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "transaction_id" to orderResult["transactionId"],
                    "order_number" to orderResult["orderNumber"],
                    "terminal_letter" to orderResult["letter"],
                    "sequence_number" to orderResult["sequenceNumber"],
                    "total_amount" to totalAmount
                ))
            )
        } catch (e: Exception) {
            logger.error("Error creando orden POS: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun getOrdersStats(): NanoHTTPD.Response {
        return try {
            val orders = database.getOrdersForDisplay()

            val byStatus = mapOf(
                "pendiente" to orders.count { it["order_status"] == "pendiente" },
                "en_preparacion" to orders.count { it["order_status"] == "en_preparacion" },
                "listo" to orders.count { it["order_status"] == "listo" }
            )

            val bySource = mapOf(
                "autoservicio" to orders.count { it["source"] == "autoservicio" },
                "pos" to orders.count { it["source"] == "pos" }
            )

            val recent = orders
                .sortedByDescending { (it["created_at"] as? Long) ?: 0L }
                .take(5)
                .map { mapOf(
                    "order_number" to it["order_number"],
                    "status" to it["order_status"],
                    "source" to it["source"],
                    "created_at" to it["created_at"]
                )}

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "stats" to mapOf(
                        "total_active" to orders.size,
                        "by_status" to byStatus,
                        "by_source" to bySource,
                        "recent_orders" to recent
                    ),
                    "timestamp" to System.currentTimeMillis()
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo estadísticas: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun getOrdersHistory(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val params = session.parms
            val limit = params["limit"]?.toIntOrNull() ?: 50
            val type = params["type"] ?: "all"

            val history = if (type == "delivered") {
                database.getDeliveredOrders(limit)
            } else {
                database.getOrdersForDisplay().take(limit)
            }

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "history" to history,
                    "count" to history.size,
                    "limit" to limit,
                    "type" to type,
                    "timestamp" to System.currentTimeMillis()
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo historial: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }

    private fun getDeliveredOrders(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return try {
            val params = session.parms
            val limit = params["limit"]?.toIntOrNull() ?: 50

            val delivered = database.getDeliveredOrders(limit)

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                gson.toJson(mapOf(
                    "success" to true,
                    "delivered_orders" to delivered,
                    "count" to delivered.size,
                    "limit" to limit,
                    "timestamp" to System.currentTimeMillis()
                ))
            )
        } catch (e: Exception) {
            logger.error("Error obteniendo órdenes entregadas: ${e.message}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "application/json",
                gson.toJson(mapOf("success" to false, "error" to e.message))
            )
        }
    }
}
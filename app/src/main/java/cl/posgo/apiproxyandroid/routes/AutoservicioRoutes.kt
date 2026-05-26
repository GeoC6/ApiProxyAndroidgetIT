package cl.posgo.apiproxyandroid.routes

import android.content.Context
import android.util.Log
import cl.posgo.apiproxyandroid.database.DatabaseManager
import cl.posgo.apiproxyandroid.services.LoggerService
import cl.posgo.apiproxyandroid.utils.Extensions.getBodyAsMap
import cl.posgo.apiproxyandroid.utils.newJsonResponse
import cl.posgo.apiproxyandroid.utils.HttpClient
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.round

class AutoservicioRoutes(
    private val context: Context,
    private val database: DatabaseManager,
    private val logger: LoggerService
) {
    private val gson = Gson()
    private val client = HttpClient.createUnsafeClient()

    companion object {
        private val INTERNAL_VOUCHER_METHODS = mapOf(
            7 to "E",
            8 to "P",
            9 to "A"
        )
    }

    suspend fun handle(
        uri: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession,
        xsignUrl: String,
        kdsUrl: String,
        mpUrl: String = "http://localhost:8001"
    ): NanoHTTPD.Response {
        return when {
            uri == "/api/autoservicio/create-order" && method == NanoHTTPD.Method.POST ->
                handleCreateOrder(session, xsignUrl, kdsUrl, mpUrl)
            uri == "/api/autoservicio/printers/list" && method == NanoHTTPD.Method.GET ->
                handlePrintersList()
            else ->
                newJsonResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    mapOf("error" to "Endpoint not found")
                )
        }
    }

    private suspend fun handleCreateOrder(
        session: NanoHTTPD.IHTTPSession,
        xsignUrl: String,
        kdsUrl: String,
        mpUrl: String
    ): NanoHTTPD.Response = withContext(Dispatchers.IO) {
        try {
            logger.info("Nueva orden de autoservicio recibida")

            val bodyMap = session.getBodyAsMap()

            val sessionId = (bodyMap["session_id"] as? Number)?.toInt()
                ?: return@withContext newJsonResponse(
                    NanoHTTPD.Response.Status.BAD_REQUEST,
                    mapOf("error" to "session_id es requerido")
                )

            val whatsappNumber = bodyMap["whatsapp_number"] as? String

            if (whatsappNumber != null) {
                logger.info("WhatsApp: $whatsappNumber")
            }

            val orders = bodyMap["orders"] as? List<*>
            val firstOrder = orders?.firstOrNull() as? Map<*, *>
            val payment = firstOrder?.get("payment") as? List<*>

            val paymentIds = payment?.mapNotNull { p ->
                val paymentMap = p as? Map<*, *>
                (paymentMap?.get("id") as? Number)?.toInt()
            } ?: emptyList()

            val isVoucher = isInternalVoucher(paymentIds)

            var dteResponse: Map<String, Any>? = null
            var voucherNumber: String? = null

            if (isVoucher) {
                logger.info("═══════════════════════════════════════════════════════════")
                logger.info(" VALE INTERNO DETECTADO - NO SE GENERARÁ DTE NI COBRO MP")
                logger.info("═══════════════════════════════════════════════════════════")
                logger.info("Métodos de pago: ${paymentIds.joinToString(", ")}")
                logger.info("Sesión: $sessionId")

                val prefix = getVoucherPrefix(paymentIds)
                voucherNumber = database.getNextInternalVoucherNumber(prefix, sessionId)

                logger.info("Número de vale generado: $voucherNumber")
                logger.info("═══════════════════════════════════════════════════════════")
            } else {
                val amount = (bodyMap["amount"] as? Number)?.toInt()
                    ?: return@withContext newJsonResponse(
                        NanoHTTPD.Response.Status.BAD_REQUEST,
                        mapOf("error" to "amount es requerido")
                    )
                val ticket = (bodyMap["ticket"] as? Number)?.toInt()
                    ?: Math.floor(Math.random() * 90000 + 10000).toInt()

                val intentId = bodyMap["intent_id"] as? String

                if (!intentId.isNullOrEmpty()) {
                    logger.info("═══════════════════════════════════════════════════════════")
                    logger.info(" PAGO APROBADO POR MP EN FRONTEND (intent: $intentId)")
                    logger.info(" Cobro ya procesado por MP — Generando DTE directamente")
                    logger.info("═══════════════════════════════════════════════════════════")
                } else {
                    logger.info("═══════════════════════════════════════════════════════════")
                    logger.info(" PASO 1: Iniciando cobro MercadoPago - Monto: $$amount")
                    logger.info("═══════════════════════════════════════════════════════════")

                    val mpOrderId = callMercadoPago(mpUrl, amount, "POS-$ticket")

                    logger.info(" PASO 2: Esperando aprobación MP (orden: $mpOrderId)...")

                    val mpResult = pollMercadoPagoStatus(mpUrl, mpOrderId)

                    logger.info(" PASO 3: Pago aprobado por MercadoPago")
                    logger.info("   - Auth: ${(mpResult["transactions"] as? Map<*, *>)?.let {
                        (it["payments"] as? List<*>)?.firstOrNull()?.let { p ->
                            (p as? Map<*, *>)?.get("id")
                        }
                    } ?: "N/A"}")
                }

                logger.info(" PASO 4: Generando DTE...")
                dteResponse = generateDTE(bodyMap, xsignUrl)

                if (dteResponse == null) {
                    return@withContext newJsonResponse(
                        NanoHTTPD.Response.Status.INTERNAL_ERROR,
                        mapOf("error" to "No se pudo generar el DTE")
                    )
                }

                logger.info("DTE generado con folio: ${dteResponse["folio"]}")
            }

            logger.info(" PASO 5: Guardando en base de datos...")
            val orderResult = database.saveTransactionWithOrder(
                data = bodyMap,
                sessionId = sessionId,
                source = if (isVoucher) "autoservicio" else "autoservicio-mp",
                terminalName = if (isVoucher) "Terminal-$sessionId" else "MP-$sessionId",
                whatsappNumber = whatsappNumber,
                dteResponse = dteResponse,
                isInternalVoucher = isVoucher,
                voucherNumber = voucherNumber
            )

            if (isVoucher) {
                logger.success("Vale interno $voucherNumber guardado como orden ${orderResult["orderNumber"]}")
            } else {
                logger.success("═══════════════════════════════════════════════════════════")
                logger.success(" ORDEN COMPLETADA: ${orderResult["orderNumber"]}")
                logger.success("   - Folio DTE: ${dteResponse?.get("folio")}")
                logger.success("═══════════════════════════════════════════════════════════")
            }

            val orderNumber = orderResult["orderNumber"]?.toString() ?: ""
            val transactionId = orderResult["transactionId"]?.toString() ?: ""

            // Enviar al KDS (fire-and-forget real — en thread separado para no bloquear la respuesta)
            Thread { sendToKDS(kdsUrl, bodyMap, orderNumber, transactionId) }.start()

            val dteTed = if (!isVoucher) dteResponse?.get("ted") as? String else null

            newJsonResponse(
                NanoHTTPD.Response.Status.OK,
                mapOf(
                    "success" to true,
                    "transaction_id" to transactionId,
                    "order_number" to orderNumber,
                    "terminal_letter" to orderResult["letter"],
                    "sequence_number" to orderResult["sequenceNumber"],
                    "dte_folio" to if (isVoucher) voucherNumber else (dteResponse?.get("folio") ?: 0),
                    "dte_ted" to (dteTed ?: ""),
                    "is_internal_voucher" to isVoucher,
                    "internal_voucher_number" to (voucherNumber ?: ""),
                    "message" to if (isVoucher) "Vale interno procesado correctamente" else "Orden procesada correctamente"
                )
            )

        } catch (e: Exception) {
            logger.error("Error procesando orden: ${e.message}")
            newJsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                mapOf("error" to e.message)
            )
        }
    }

    private suspend fun callMercadoPago(mpUrl: String, amount: Int, reference: String): String =
        withContext(Dispatchers.IO) {
            val payload = """{"amount": "$amount", "reference": "$reference"}"""
            val requestBody = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$mpUrl/mp_pay")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                throw Exception("MercadoPago error al crear orden: $responseBody")
            }

            val json = gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
            json["id"] as? String ?: throw Exception("MercadoPago no retornó ID de orden")
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun pollMercadoPagoStatus(mpUrl: String, orderId: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val maxAttempts = 40
            val delayMs = 3000L

            repeat(maxAttempts) { attempt ->
                val request = Request.Builder()
                    .url("$mpUrl/mp_status/$orderId")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val json = gson.fromJson(
                    response.body?.string() ?: "{}",
                    Map::class.java
                ) as Map<String, Any>

                val status = json["status"] as? String ?: ""

                logger.info("   MP status (intento ${attempt + 1}/$maxAttempts): $status")

                when (status) {
                    "processed" -> return@withContext json
                    "cancelled", "canceled", "expired" ->
                        throw Exception("Pago rechazado por MercadoPago: $status")
                }

                if (attempt < maxAttempts - 1) {
                    delay(delayMs)
                }
            }

            throw Exception("Timeout: MercadoPago no respondió en 120 segundos")
        }

    private fun handlePrintersList(): NanoHTTPD.Response {
        val printers = listOf(
            mapOf("name" to "POS", "available" to true)
        )

        return newJsonResponse(
            NanoHTTPD.Response.Status.OK,
            mapOf(
                "success" to true,
                "count" to printers.size,
                "printers" to printers
            )
        )
    }

    private fun isInternalVoucher(paymentIds: List<Int>): Boolean {
        if (paymentIds.isEmpty()) return false
        return paymentIds.all { INTERNAL_VOUCHER_METHODS.containsKey(it) }
    }

    private fun getVoucherPrefix(paymentIds: List<Int>): String {
        val firstValidId = paymentIds.firstOrNull { INTERNAL_VOUCHER_METHODS.containsKey(it) }
        return INTERNAL_VOUCHER_METHODS[firstValidId] ?: "V"
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendToKDS(kdsUrl: String, bodyMap: Map<*, *>, orderNumber: String, transactionId: String) {
        logger.info("[KDS] ── Iniciando envío al KDS ──")
        if (kdsUrl.isBlank()) {
            logger.warn("[KDS] KDS_URL vacía — omitiendo envío")
            return
        }
        logger.info("[KDS] URL destino: $kdsUrl/api/kds/new-order")
        try {
            val orders = bodyMap["orders"] as? List<*> ?: emptyList<Any>()
            val firstOrder = orders.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val products = firstOrder["products"] as? List<*> ?: emptyList<Any>()

            logger.info("[KDS] Productos en la orden: ${products.size}")

            val kdsItems = products.map { p ->
                val prod = p as? Map<*, *> ?: emptyMap<Any?, Any?>()
                val name = prod["name"] ?: ""
                val qty = (prod["qty"] as? Number)?.toInt() ?: 1
                val catId = (prod["category_id"] as? Number)?.toInt()
                val catName = prod["category_name"] as? String ?: ""
                mapOf(
                    "name" to name,
                    "cant" to qty,
                    "notes" to (prod["customization"] ?: ""),
                    "category_id" to catId,
                    "category_name" to catName,
                    "attribute_lines" to (prod["attribute_lines"] ?: emptyList<Any>())
                )
            }

            val customerComment = firstOrder["customer_comment"] as? String ?: ""

            val kdsPayload = gson.toJson(mapOf(
                "external_id" to transactionId,
                "order_number" to orderNumber,
                "items" to kdsItems,
                "source" to "autoservicio",
                "customer_name" to "Cliente",
                "note" to customerComment
            ))

            logger.info("[KDS] Payload listo (${kdsPayload.length} bytes) — enviando request...")

            val request = Request.Builder()
                .url("$kdsUrl/api/kds/new-order")
                .post(kdsPayload.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (response.isSuccessful) {
                logger.success("[KDS] ✓ Orden $orderNumber enviada — respuesta ${response.code}: $responseBody")
                Log.i("KDS", "Orden $orderNumber enviada al KDS ($kdsUrl) - respuesta: $responseBody")
            } else {
                logger.warn("[KDS] ✗ KDS respondió ${response.code}: $responseBody")
                Log.w("KDS", "KDS error ${response.code}: $responseBody")
            }
        } catch (e: java.net.ConnectException) {
            logger.error("[KDS] ✗ No se pudo conectar a $kdsUrl — ¿está el KDS encendido? (${e.message})")
            Log.e("KDS", "ConnectException: ${e.message}", e)
        } catch (e: java.net.SocketTimeoutException) {
            logger.error("[KDS] ✗ Timeout conectando a $kdsUrl (${e.message})")
            Log.e("KDS", "SocketTimeoutException: ${e.message}", e)
        } catch (e: Exception) {
            logger.error("[KDS] ✗ Error inesperado: ${e.javaClass.simpleName} — ${e.message}")
            Log.e("KDS", "Error enviando al KDS: ${e.message}", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun generateDTE(
        bodyMap: Map<*, *>,
        xsignUrl: String
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val sessionData = bodyMap["session_data"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val dteData = buildDTEData(bodyMap, sessionData)

            val requestBody = gson.toJson(dteData).toRequestBody("application/json".toMediaType())

            val url = "$xsignUrl/sign/39?getTED=false&sendDTE=true"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "*/*")
                .build()

            logger.info("[DTE] Enviando a XSign URL: $url")
            logger.info("[DTE] Payload completo:\n${gson.toJson(dteData)}")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                logger.error("[DTE] XSign respondió ${response.code}: $responseBody")
                return@withContext null
            }

            logger.info("[DTE] XSign OK: $responseBody")

            gson.fromJson(responseBody, Map::class.java) as Map<String, Any>

        } catch (e: Exception) {
            logger.error("Error en generateDTE: ${e.message}")
            null
        }
    }

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("\t", " ")
        .trim()

    // Convierte á→a, ñ→n, etc. antes de limpiar
    private fun String.normalizeNFD(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}"), "")

    // Limpia para SII: normaliza acentos, elimina no-ASCII, trunca y escapa XML
    private fun String.sanitizeForSII(maxLen: Int = 80): String = this
        .normalizeNFD()
        .replace(Regex("[^\\x20-\\x7E]"), " ")
        .trim()
        .take(maxLen)
        .escapeXml()

    private fun buildDTEData(
        bodyMap: Map<*, *>,
        sessionData: Map<*, *>
    ): HashMap<String, Any> {
        val orders = bodyMap["orders"] as? List<*> ?: emptyList<Any>()
        val firstOrder = orders.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val products = firstOrder["products"] as? List<*> ?: emptyList<Any>()

        val totalAmount = products.sumOf { product ->
            val p = product as Map<*, *>
            (p["price_subtotal"] as? Number)?.toDouble() ?: 0.0
        }

        val dscRcgGlobalAmount = ((firstOrder["dsc_rcg_global"] as? Number)?.toDouble() ?: 0.0)
        val mntTotal = round(totalAmount - dscRcgGlobalAmount).toInt()
        val mntNeto = round(mntTotal / 1.19).toInt()
        val iva = mntTotal - mntNeto

        val companyData = sessionData["company_data"] as? Map<*, *>

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val currentDate = dateFormat.format(Date())

        val detalleItems = products.mapIndexed { index, product ->
            val p = product as Map<*, *>
            val productId = (p["product_id"] as? Number)?.toInt() ?: (999000 + index)
            val qty = (p["qty"] as? Number)?.toInt() ?: 1
            val priceSubtotal = (p["price_subtotal"] as? Number)?.toDouble() ?: 0.0
            val unitPrice = if (qty > 0) round(priceSubtotal / qty).toInt() else 0

            val cdgItem = hashMapOf<String, Any>(
                "TpoCodigo" to "INT1",
                "VlrCodigo" to productId.toString()
            )

            val rawName = p["name"] as? String ?: "Producto ${index + 1}"
            val rawCustomization = p["customization"] as? String ?: "N/A"
            val sanitizedName = rawName.sanitizeForSII(70)
            val sanitizedDesc = rawCustomization.sanitizeForSII(1000)

            logger.info("[DTE] Producto ${index + 1}: '$rawName' → '$sanitizedName'")
            if (rawName != sanitizedName) {
                logger.info("[DTE]   ⚠ Nombre modificado por sanitización")
            }
            if (rawCustomization.length > 1000) {
                logger.info("[DTE]   ⚠ Descripción truncada de ${rawCustomization.length} a 1000 chars")
            }

            hashMapOf<String, Any>(
                "NroLinDet" to (index + 1),
                "CdgItem" to cdgItem,
                "NmbItem" to sanitizedName,
                "DscItem" to sanitizedDesc,
                "QtyItem" to qty,
                "PrcItem" to unitPrice,
                "MontoItem" to round(priceSubtotal).toInt()
            )
        }

        val idDoc = hashMapOf<String, Any>(
            "TipoDTE" to 39,
            "FchEmis" to currentDate
        )

        val emisor = hashMapOf<String, Any>(
            "RUTEmisor" to (companyData?.get("vat") ?: "76.000.000-0"),
            "RznSoc" to (companyData?.get("name") as? String ?: "AUTOSERVICIO").escapeXml(),
            "GiroEmis" to (companyData?.get("turn") as? String ?: "COMERCIO").escapeXml(),
            "Acteco" to (companyData?.get("acteco") ?: "471100"),
            "DirOrigen" to (companyData?.get("street") as? String ?: "N/A").escapeXml(),
            "CmnaOrigen" to (companyData?.get("city") as? String ?: "N/A").escapeXml(),
            "CdgVendedor" to "autoservicio"
        )

        val receptor = hashMapOf<String, Any>(
            "RUTRecep" to "66666666-6",
            "RznSocRecep" to "CLIENTE AUTOSERVICIO",
            "DirRecep" to "N/A",
            "CmnaRecep" to "N/A"
        )

        val totales = hashMapOf<String, Any>(
            "MntNeto" to mntNeto,
            "IVA" to iva,
            "MntTotal" to mntTotal
        ).also { if (dscRcgGlobalAmount > 0) it["MntDesc"] = round(dscRcgGlobalAmount).toInt() }

        val encabezado = hashMapOf<String, Any>(
            "IdDoc" to idDoc,
            "Emisor" to emisor,
            "Receptor" to receptor,
            "Totales" to totales
        )

        val paymentMethodType = (firstOrder["payment"] as? List<*>)
            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("payment_method_type") as? String } ?: ""
        val paymentDesc = if (paymentMethodType.contains("debit", ignoreCase = true)) "DEBITO" else "CREDITO"
        logger.info("[DTE] payment_method_type recibido: '$paymentMethodType' → desc: '$paymentDesc'")

        val pagoItem = hashMapOf<String, Any>(
            "desc" to paymentDesc,
            "monto" to mntTotal
        )

        val infoPagos = hashMapOf<String, Any>(
            "Propina" to 0,
            "CdgVendedor" to "autoservicio",
            "AjusteSencillo" to 0,
            "Vuelto" to 0,
            "Pagos" to listOf(pagoItem)
        )

        return hashMapOf(
            "Encabezado" to encabezado,
            "infoPagos" to infoPagos,
            "Detalle" to detalleItems,
            "DscRcgGlobal" to if (dscRcgGlobalAmount > 0) listOf(
                hashMapOf<String, Any>(
                    "NroLinDR" to 1,
                    "TpoMov" to "D",
                    "TpoValor" to "$",
                    "ValorDR" to round(dscRcgGlobalAmount).toInt()
                )
            ) else emptyList<Any>(),
            "session_id" to ((bodyMap["session_id"] as? Number)?.toInt() ?: 0)
        )
    }
}
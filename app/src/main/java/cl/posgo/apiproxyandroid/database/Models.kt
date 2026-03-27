package cl.posgo.apiproxyandroid.database

data class Transaction(
    val id: Long,
    val transactionData: String,
    val sessionId: Int?,
    val source: String?,
    val terminalName: String?,
    val whatsappNumber: String?,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long,
    val processedAt: Long?,
    val statusUpdatedAt: Long?,
    val folioDte: String?,
    val dteResponse: String?,
    val orderNumber: String?,
    val orderStatus: String?,
    val terminalLetter: String?,
    val sequenceNumber: Int?,
    val isInternalVoucher: Int?,
    val internalVoucherNumber: String?
)

data class OrderResult(
    val transactionId: Long,
    val orderNumber: String,
    val letter: String,
    val sequenceNumber: Int
)

data class CriticalError(
    val id: Long,
    val message: String,
    val type: String,
    val source: String,
    val timestamp: String,
    val transactionId: Long?
)
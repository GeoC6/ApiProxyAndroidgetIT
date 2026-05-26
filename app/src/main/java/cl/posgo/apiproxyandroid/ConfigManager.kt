package cl.posgo.apiproxyandroid

import android.content.Context
import android.content.SharedPreferences

object ConfigManager {

    private const val PREFS_NAME = "api_config"

    private const val KEY_ODOO_URL = "ODOO_URL"
    private const val KEY_XSIGN_URL = "XSIGN_URL"
    private const val KEY_KDS_URL = "KDS_URL"
    private const val KEY_PRINTER_NAME = "PRINTER_TICKET_NAME"
    private const val KEY_N8N_WEBHOOK = "N8N_WEBHOOK_URL"
    private const val KEY_MP_URL = "MP_URL"
    private const val KEY_POS_PIN = "POS_PIN"

    private const val DEFAULT_ODOO_URL = "https://getit.posgo.cl"
    private const val DEFAULT_XSIGN_URL = "http://localhost:5999"
    private const val DEFAULT_KDS_URL = "http://192.168.7.172:9001"
    private const val DEFAULT_PRINTER_NAME = "POS"
    private const val DEFAULT_MP_URL = "http://localhost:8001"

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class Config(
        val odooUrl: String,
        val xsignUrl: String,
        val kdsUrl: String,
        val printerName: String,
        val n8nWebhookUrl: String,
        val mpUrl: String,
        val posPin: String
    )

    fun getConfig(): Config {
        return Config(
            odooUrl = prefs.getString(KEY_ODOO_URL, DEFAULT_ODOO_URL) ?: DEFAULT_ODOO_URL,
            xsignUrl = prefs.getString(KEY_XSIGN_URL, DEFAULT_XSIGN_URL) ?: DEFAULT_XSIGN_URL,
            kdsUrl = prefs.getString(KEY_KDS_URL, DEFAULT_KDS_URL) ?: DEFAULT_KDS_URL,
            printerName = prefs.getString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME) ?: DEFAULT_PRINTER_NAME,
            n8nWebhookUrl = prefs.getString(KEY_N8N_WEBHOOK, "") ?: "",
            mpUrl = prefs.getString(KEY_MP_URL, DEFAULT_MP_URL) ?: DEFAULT_MP_URL,
            posPin = prefs.getString(KEY_POS_PIN, "") ?: ""
        )
    }

    fun getPosPin(): String = prefs.getString(KEY_POS_PIN, "") ?: ""

    fun updateConfig(
        odooUrl: String? = null,
        xsignUrl: String? = null,
        kdsUrl: String? = null,
        printerName: String? = null,
        n8nWebhookUrl: String? = null,
        mpUrl: String? = null,
        posPin: String? = null
    ) {
        prefs.edit().apply {
            odooUrl?.let { putString(KEY_ODOO_URL, it) }
            xsignUrl?.let { putString(KEY_XSIGN_URL, it) }
            kdsUrl?.let { putString(KEY_KDS_URL, it) }
            printerName?.let { putString(KEY_PRINTER_NAME, it) }
            n8nWebhookUrl?.let { putString(KEY_N8N_WEBHOOK, it) }
            mpUrl?.let { putString(KEY_MP_URL, it) }
            posPin?.let { putString(KEY_POS_PIN, it) }
            apply()
        }
    }

    fun isConfigured(): Boolean {
        val config = getConfig()
        return config.odooUrl.isNotEmpty()
    }

    fun resetToDefaults() {
        prefs.edit().apply {
            putString(KEY_ODOO_URL, DEFAULT_ODOO_URL)
            putString(KEY_XSIGN_URL, DEFAULT_XSIGN_URL)
            putString(KEY_KDS_URL, DEFAULT_KDS_URL)
            putString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME)
            putString(KEY_N8N_WEBHOOK, "")
            putString(KEY_MP_URL, DEFAULT_MP_URL)
            apply()
        }
    }
}
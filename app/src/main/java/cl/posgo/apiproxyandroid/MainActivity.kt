package cl.posgo.apiproxyandroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var configStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var toggleConfigButton: Button
    private lateinit var saveConfigButton: Button
    private lateinit var configFieldsLayout: LinearLayout

    private lateinit var odooUrlInput: TextInputEditText
    private lateinit var xsignUrlInput: TextInputEditText
    private lateinit var kdsUrlInput: TextInputEditText
    private lateinit var printerNameInput: TextInputEditText
    private lateinit var n8nWebhookInput: TextInputEditText

    private var configVisible = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.i("ApiProxyAndroid", "Permiso de notificación concedido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.initialize(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        initializeViews()
        setupListeners()
        loadConfigToUI()
        updateConfigStatus()

        startApiService()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        configStatusText = findViewById(R.id.configStatusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        toggleConfigButton = findViewById(R.id.toggleConfigButton)
        saveConfigButton = findViewById(R.id.saveConfigButton)
        configFieldsLayout = findViewById(R.id.configFieldsLayout)

        odooUrlInput = findViewById(R.id.odooUrlInput)
        xsignUrlInput = findViewById(R.id.xsignUrlInput)
        kdsUrlInput = findViewById(R.id.kdsUrlInput)
        printerNameInput = findViewById(R.id.printerNameInput)
        n8nWebhookInput = findViewById(R.id.n8nWebhookInput)
    }

    private fun setupListeners() {
        startButton.setOnClickListener {
            startApiService()
        }

        stopButton.setOnClickListener {
            stopApiService()
        }

        toggleConfigButton.setOnClickListener {
            toggleConfigVisibility()
        }

        saveConfigButton.setOnClickListener {
            saveConfiguration()
        }
    }

    private fun loadConfigToUI() {
        val config = ConfigManager.getConfig()
        odooUrlInput.setText(config.odooUrl)
        xsignUrlInput.setText(config.xsignUrl)
        kdsUrlInput.setText(config.kdsUrl)
        printerNameInput.setText(config.printerName)
        n8nWebhookInput.setText(config.n8nWebhookUrl)
    }

    private fun updateConfigStatus() {
        val config = ConfigManager.getConfig()
        configStatusText.text = """
            Odoo: ${config.odooUrl}
            XSign: ${config.xsignUrl}
            KDS: ${config.kdsUrl}
            Impresora: ${config.printerName}
        """.trimIndent()
    }

    private fun toggleConfigVisibility() {
        configVisible = !configVisible
        configFieldsLayout.visibility = if (configVisible) View.VISIBLE else View.GONE
        toggleConfigButton.text = if (configVisible) "Ocultar" else "Editar"
    }

    private fun saveConfiguration() {
        ConfigManager.updateConfig(
            odooUrl = odooUrlInput.text.toString(),
            xsignUrl = xsignUrlInput.text.toString(),
            kdsUrl = kdsUrlInput.text.toString(),
            printerName = printerNameInput.text.toString(),
            n8nWebhookUrl = n8nWebhookInput.text.toString()
        )

        updateConfigStatus()
        configVisible = false
        configFieldsLayout.visibility = View.GONE
        toggleConfigButton.text = "Editar"

        statusText.text = "Configuración guardada\nReinicie el servicio para aplicar cambios"
    }

    private fun startApiService() {
        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "Iniciando servicio..."
            ApiService.start(this@MainActivity)
            delay(2000)
            statusText.text = "Servidor ejecutándose en:\nhttp://localhost:9000"
            startButton.isEnabled = false
            stopButton.isEnabled = true
        }
    }

    private fun stopApiService() {
        ApiService.stop(this)
        statusText.text = "Servidor detenido"
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }
}
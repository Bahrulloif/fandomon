package com.tastamat.fandomon

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.network.NetworkSender
import kotlinx.coroutines.launch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : AppCompatActivity() {

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var networkSender: NetworkSender

    private lateinit var serverUrlEdit: EditText
    private lateinit var mqttBrokerEdit: EditText
    private lateinit var monitoringIntervalEdit: EditText
    private lateinit var statusSendIntervalEdit: EditText
    private lateinit var logRetentionEdit: EditText
    private lateinit var maxEventsPerBatchEdit: EditText
    private lateinit var deviceIdEdit: EditText
    private lateinit var deviceNameEdit: EditText
    private lateinit var inactivityTimeoutEdit: EditText
    private lateinit var autoRestartSwitch: Switch
    private lateinit var connectionStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeComponents()
        setupViews()
        loadSettings()
    }

    private fun initializeComponents() {
        databaseHelper = DatabaseHelper(this)
        networkSender = NetworkSender(this)
    }

    private fun setupViews() {
        serverUrlEdit = findViewById(R.id.serverUrlEdit)
        mqttBrokerEdit = findViewById(R.id.mqttBrokerEdit)
        monitoringIntervalEdit = findViewById(R.id.monitoringIntervalEdit)
        statusSendIntervalEdit = findViewById(R.id.statusSendIntervalEdit)
        logRetentionEdit = findViewById(R.id.logRetentionEdit)
        maxEventsPerBatchEdit = findViewById(R.id.maxEventsPerBatchEdit)
        deviceIdEdit = findViewById(R.id.deviceIdEdit)
        deviceNameEdit = findViewById(R.id.deviceNameEdit)
        inactivityTimeoutEdit = findViewById(R.id.inactivityTimeoutEdit)
        autoRestartSwitch = findViewById(R.id.autoRestartSwitch)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveSettings()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.testRestButton).setOnClickListener {
            testRestConnection()
        }

        findViewById<Button>(R.id.testMqttButton).setOnClickListener {
            testMqttConnection()
        }

        findViewById<Button>(R.id.resetSettingsButton).setOnClickListener {
            resetToDefaults()
        }

        findViewById<Button>(R.id.exportSettingsButton).setOnClickListener {
            exportSettings()
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            try {
                serverUrlEdit.setText(databaseHelper.getSetting("server_url"))
                mqttBrokerEdit.setText(databaseHelper.getSetting("mqtt_broker"))
                monitoringIntervalEdit.setText(databaseHelper.getSetting("monitoring_interval", "30"))
                statusSendIntervalEdit.setText(databaseHelper.getSetting("status_send_interval", "300"))
                logRetentionEdit.setText(databaseHelper.getSetting("log_retention_days", "7"))
                maxEventsPerBatchEdit.setText(databaseHelper.getSetting("max_events_per_batch", "100"))

                // Загружаем device_id (если пустое, показываем автоматическое)
                val savedDeviceId = databaseHelper.getSetting("device_id", "")
                if (savedDeviceId.isEmpty()) {
                    val autoDeviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                    deviceIdEdit.hint = "Авто: $autoDeviceId"
                    deviceIdEdit.setText("")
                } else {
                    deviceIdEdit.setText(savedDeviceId)
                }

                deviceNameEdit.setText(databaseHelper.getSetting("device_name", android.os.Build.MODEL))
                inactivityTimeoutEdit.setText(databaseHelper.getSetting("inactivity_timeout_minutes", "5"))
                autoRestartSwitch.isChecked = databaseHelper.getSetting("auto_restart_fandomat", "true") == "true"
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Ошибка загрузки настроек", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSettings() {
        lifecycleScope.launch {
            try {
                // Валидация данных
                val monitoringInterval = monitoringIntervalEdit.text.toString().toIntOrNull()
                val statusSendInterval = statusSendIntervalEdit.text.toString().toIntOrNull()
                val logRetention = logRetentionEdit.text.toString().toIntOrNull()
                val maxEvents = maxEventsPerBatchEdit.text.toString().toIntOrNull()
                val inactivityTimeout = inactivityTimeoutEdit.text.toString().toIntOrNull()

                if (monitoringInterval == null || monitoringInterval < 10) {
                    Toast.makeText(this@SettingsActivity, "Интервал мониторинга должен быть >= 10 сек", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (statusSendInterval == null || statusSendInterval < 60) {
                    Toast.makeText(this@SettingsActivity, "Интервал отправки статуса должен быть >= 60 сек", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (inactivityTimeout == null || inactivityTimeout < 1) {
                    Toast.makeText(this@SettingsActivity, "Время неактивности должно быть >= 1 мин", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                databaseHelper.saveSetting("server_url", serverUrlEdit.text.toString().trim())
                databaseHelper.saveSetting("mqtt_broker", mqttBrokerEdit.text.toString().trim())
                databaseHelper.saveSetting("monitoring_interval", monitoringInterval.toString())
                databaseHelper.saveSetting("status_send_interval", statusSendInterval.toString())
                databaseHelper.saveSetting("log_retention_days", logRetention?.toString() ?: "7")
                databaseHelper.saveSetting("max_events_per_batch", maxEvents?.toString() ?: "100")

                // Сохраняем device_id (если пустое, оставляем пустым для автоматического)
                val deviceIdText = deviceIdEdit.text.toString().trim()
                databaseHelper.saveSetting("device_id", deviceIdText)

                databaseHelper.saveSetting("device_name", deviceNameEdit.text.toString().trim())
                databaseHelper.saveSetting("inactivity_timeout_minutes", inactivityTimeout.toString())
                databaseHelper.saveSetting("auto_restart_fandomat", if (autoRestartSwitch.isChecked) "true" else "false")

                Toast.makeText(this@SettingsActivity, "✅ Настройки сохранены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "❌ Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testRestConnection() {
        lifecycleScope.launch {
            try {
                val serverUrl = serverUrlEdit.text.toString().trim()
                if (serverUrl.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Введите URL сервера", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                connectionStatusText.text = "🔄 Тестирование REST соединения..."

                networkSender.testConnection(serverUrl) { success, message ->
                    runOnUiThread {
                        connectionStatusText.text = if (success) {
                            "✅ REST: $message"
                        } else {
                            "❌ REST: $message"
                        }
                    }
                }

            } catch (e: Exception) {
                connectionStatusText.text = "❌ REST: Ошибка теста - ${e.message}"
            }
        }
    }

    private fun testMqttConnection() {
        lifecycleScope.launch {
            try {
                val mqttBroker = mqttBrokerEdit.text.toString().trim()
                if (mqttBroker.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "Введите MQTT брокер", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                connectionStatusText.text = "🔄 Тестирование MQTT соединения..."

                networkSender.testMqttConnection(mqttBroker) { success, message ->
                    runOnUiThread {
                        connectionStatusText.text = if (success) {
                            "✅ MQTT: $message"
                        } else {
                            "❌ MQTT: $message"
                        }
                    }
                }

            } catch (e: Exception) {
                connectionStatusText.text = "❌ MQTT: Ошибка теста - ${e.message}"
            }
        }
    }

    private fun resetToDefaults() {
        serverUrlEdit.setText("")
        mqttBrokerEdit.setText("")
        monitoringIntervalEdit.setText("30")
        statusSendIntervalEdit.setText("300")
        logRetentionEdit.setText("7")
        maxEventsPerBatchEdit.setText("100")

        // Сбрасываем device_id к автоматическому
        deviceIdEdit.setText("")
        val autoDeviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        deviceIdEdit.hint = "Авто: $autoDeviceId"

        deviceNameEdit.setText(android.os.Build.MODEL)
        inactivityTimeoutEdit.setText("5")
        autoRestartSwitch.isChecked = true
        connectionStatusText.text = "ℹ️ Настройки сброшены к значениям по умолчанию"
        Toast.makeText(this, "Настройки сброшены", Toast.LENGTH_SHORT).show()
    }

    private fun exportSettings() {
        lifecycleScope.launch {
            try {
                val settings = databaseHelper.getAllSettings()
                val settingsText = settings.map { "${it.key} = ${it.value}" }.joinToString("\n")

                // Показываем настройки в диалоге или копируем в буфер
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Настройки Fandomon", settingsText)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(this@SettingsActivity, "📋 Настройки скопированы в буфер обмена", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "❌ Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
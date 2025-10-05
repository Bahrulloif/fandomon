package com.tastamat.fandomon.network

import android.content.Context
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.Event
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

class NetworkSender(private val context: Context) {

    companion object {
        private const val TAG = "NetworkSender"
        private const val DEFAULT_TIMEOUT = 30000 // 30 секунд
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY = 5000L // 5 секунд

        // MQTT настройки
        private const val MQTT_QOS = 1
        private const val MQTT_TOPIC_EVENTS = "fandomon/events"
        private const val MQTT_TOPIC_STATUS = "fandomon/status"
        private const val MQTT_CLIENT_ID_PREFIX = "fandomon_"
    }

    private val databaseHelper = DatabaseHelper(context)
    private val executor = Executors.newFixedThreadPool(2)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mqttClient: MqttClient? = null

    /**
     * Отправляет событие на сервер
     */
    fun sendEvent(event: Event, callback: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                val success = sendEventInternal(event)
                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки события", e)
                callback?.invoke(false)
            }
        }
    }

    /**
     * Отправляет статус на сервер
     */
    fun sendStatus(status: Map<String, Any?>) {
        scope.launch {
            try {
                sendStatusInternal(status)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки статуса", e)
            }
        }
    }

    /**
     * Внутренний метод отправки события
     */
    private suspend fun sendEventInternal(event: Event): Boolean {
        val serverUrl = databaseHelper.getSetting("server_url")
        val mqttBroker = databaseHelper.getSetting("mqtt_broker")

        var success = false

        // Попытка отправки через REST API
        if (serverUrl.isNotEmpty()) {
            success = sendViaRest(event, serverUrl)
        }

        // Если REST не удался, пробуем MQTT
        if (!success && mqttBroker.isNotEmpty()) {
            success = sendViaMqtt(event, mqttBroker)
        }

        return success
    }

    /**
     * Отправка через REST API
     */
    private suspend fun sendViaRest(event: Event, serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            var attempt = 0
            while (attempt < MAX_RETRIES) {
                try {
                    val url = URL("$serverUrl/api/events")
                    val connection = url.openConnection() as HttpURLConnection

                    connection.apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("User-Agent", "Fandomon/1.0")
                        connectTimeout = DEFAULT_TIMEOUT
                        readTimeout = DEFAULT_TIMEOUT
                        doOutput = true
                    }

                    // Создаем JSON из события
                    val jsonData = createEventJson(event)

                    // Отправляем данные
                    connection.outputStream.use { outputStream ->
                        outputStream.write(jsonData.toByteArray())
                        outputStream.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode in 200..299) {
                        Log.i(TAG, "Событие успешно отправлено через REST: ${event.id}")
                        return@withContext true
                    } else {
                        Log.w(TAG, "Ошибка REST API: код $responseCode")
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "Ошибка соединения REST, попытка ${attempt + 1}", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Неожиданная ошибка REST, попытка ${attempt + 1}", e)
                }

                attempt++
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY)
                }
            }

            false
        }
    }

    /**
     * Отправка через MQTT
     */
    private suspend fun sendViaMqtt(event: Event, mqttBroker: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = getOrCreateMqttClient(mqttBroker)
                if (client == null || !client.isConnected) {
                    Log.w(TAG, "MQTT клиент не подключен")
                    return@withContext false
                }

                val jsonData = createEventJson(event)
                val message = MqttMessage(jsonData.toByteArray())
                message.qos = MQTT_QOS

                client.publish(MQTT_TOPIC_EVENTS, message)
                Log.i(TAG, "Событие успешно отправлено через MQTT: ${event.id}")
                true

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT ошибка при отправке события", e)
                // Пытаемся переподключиться при ошибке
                disconnectMqtt()
                false
            } catch (e: Exception) {
                Log.e(TAG, "Общая ошибка MQTT отправки", e)
                false
            }
        }
    }

    /**
     * Получает или создает MQTT клиент
     */
    private fun getOrCreateMqttClient(brokerUrl: String): MqttClient? {
        try {
            if (mqttClient?.isConnected == true) {
                return mqttClient
            }

            // Создаем уникальный client ID
            val deviceId = getDeviceId()
            val clientId = "$MQTT_CLIENT_ID_PREFIX$deviceId"

            mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 30
                keepAliveInterval = 60
                isAutomaticReconnect = true
                maxInflight = 10
            }

            mqttClient?.connect(options)
            Log.i(TAG, "MQTT клиент подключен к $brokerUrl")

            // Устанавливаем callback для мониторинга подключения
            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT соединение потеряно", cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // Обработка входящих сообщений (если нужно)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d(TAG, "MQTT сообщение доставлено")
                }
            })

            return mqttClient

        } catch (e: MqttException) {
            Log.e(TAG, "Ошибка подключения MQTT", e)
            mqttClient = null
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Неожиданная ошибка MQTT", e)
            mqttClient = null
            return null
        }
    }

    /**
     * Отключает MQTT клиент
     */
    private fun disconnectMqtt() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
            Log.d(TAG, "MQTT клиент отключен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отключения MQTT", e)
        }
    }

    /**
     * Отправляет статус через MQTT
     */
    private suspend fun sendStatusViaMqtt(status: Map<String, Any?>, brokerUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = getOrCreateMqttClient(brokerUrl)
                if (client == null || !client.isConnected) {
                    Log.w(TAG, "MQTT клиент не подключен для отправки статуса")
                    return@withContext false
                }

                val jsonData = createStatusJson(status)
                val message = MqttMessage(jsonData.toByteArray())
                message.qos = MQTT_QOS

                client.publish(MQTT_TOPIC_STATUS, message)
                Log.i(TAG, "Статус успешно отправлен через MQTT")
                true

            } catch (e: MqttException) {
                Log.e(TAG, "MQTT ошибка при отправке статуса", e)
                disconnectMqtt()
                false
            } catch (e: Exception) {
                Log.e(TAG, "Общая ошибка MQTT отправки статуса", e)
                false
            }
        }
    }

    /**
     * Отправка статуса
     */
    private suspend fun sendStatusInternal(status: Map<String, Any?>) {
        val serverUrl = databaseHelper.getSetting("server_url")
        val mqttBroker = databaseHelper.getSetting("mqtt_broker")

        var success = false

        // Попытка отправки через REST API
        if (serverUrl.isNotEmpty()) {
            success = sendStatusViaRest(status, serverUrl)
        }

        // Если REST не удался, пробуем MQTT
        if (!success && mqttBroker.isNotEmpty()) {
            sendStatusViaMqtt(status, mqttBroker)
        }
    }

    /**
     * Отправка статуса через REST API
     */
    private suspend fun sendStatusViaRest(status: Map<String, Any?>, serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/status")
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "Fandomon/1.0")
                    connectTimeout = DEFAULT_TIMEOUT
                    readTimeout = DEFAULT_TIMEOUT
                    doOutput = true
                }

                // Создаем JSON из статуса
                val jsonData = createStatusJson(status)

                // Отправляем данные
                connection.outputStream.use { outputStream ->
                    outputStream.write(jsonData.toByteArray())
                    outputStream.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Log.i(TAG, "Статус успешно отправлен через REST")
                    true
                } else {
                    Log.w(TAG, "Ошибка отправки статуса через REST: код $responseCode")
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки статуса через REST", e)
                false
            }
        }
    }

    /**
     * Создает JSON из события
     */
    private fun createEventJson(event: Event): String {
        val deviceId = getDeviceId()
        val deviceName = getDeviceName()

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("event_id", event.id)
            put("type", event.type.value)
            put("details", event.details)
            put("timestamp", event.timestamp)
            put("severity", event.severity.value)
            put("additional_data", event.additionalData ?: JSONObject.NULL)
            put("app_version", getAppVersion())
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
        }

        return json.toString()
    }

    /**
     * Создает JSON из статуса
     */
    private fun createStatusJson(status: Map<String, Any?>): String {
        val deviceId = getDeviceId()
        val deviceName = getDeviceName()

        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("app_version", getAppVersion())
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)

            // Добавляем все поля статуса
            status.forEach { (key, value) ->
                put(key, value ?: JSONObject.NULL)
            }
        }

        return json.toString()
    }

    /**
     * Получает версию приложения
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Тестирует соединение с сервером
     */
    fun testConnection(serverUrl: String, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val result = testConnectionInternal(serverUrl)
                callback(result.first, result.second)
            } catch (e: Exception) {
                callback(false, "Исключение: ${e.message}")
            }
        }
    }

    private suspend fun testConnectionInternal(serverUrl: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/ping")
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 10000 // 10 секунд для теста
                    readTimeout = 10000
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    Pair(true, "Соединение успешно (код: $responseCode)")
                } else {
                    Pair(false, "Ошибка сервера (код: $responseCode)")
                }

            } catch (e: IOException) {
                Pair(false, "Ошибка соединения: ${e.message}")
            } catch (e: Exception) {
                Pair(false, "Неожиданная ошибка: ${e.message}")
            }
        }
    }

    /**
     * Отправляет пакет событий
     */
    fun sendEventBatch(events: List<Event>, callback: ((Int) -> Unit)? = null) {
        scope.launch {
            try {
                var successCount = 0
                events.forEach { event ->
                    if (sendEventInternal(event)) {
                        successCount++
                        databaseHelper.markEventAsSent(event.id)
                    }
                }
                callback?.invoke(successCount)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки пакета событий", e)
                callback?.invoke(0)
            }
        }
    }

    /**
     * Тестирует MQTT соединение
     */
    fun testMqttConnection(brokerUrl: String, callback: (Boolean, String) -> Unit) {
        scope.launch {
            try {
                val result = testMqttConnectionInternal(brokerUrl)
                callback(result.first, result.second)
            } catch (e: Exception) {
                callback(false, "Исключение: ${e.message}")
            }
        }
    }

    private suspend fun testMqttConnectionInternal(brokerUrl: String): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val deviceId = getDeviceId()
                val clientId = "${MQTT_CLIENT_ID_PREFIX}test_$deviceId"

                val testClient = MqttClient(brokerUrl, clientId, MemoryPersistence())

                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                }

                testClient.connect(options)

                if (testClient.isConnected) {
                    testClient.disconnect()
                    testClient.close()
                    Pair(true, "MQTT соединение успешно")
                } else {
                    Pair(false, "Не удалось подключиться к MQTT брокеру")
                }

            } catch (e: MqttException) {
                Pair(false, "MQTT ошибка: ${e.message}")
            } catch (e: Exception) {
                Pair(false, "Неожиданная ошибка: ${e.message}")
            }
        }
    }

    /**
     * Проверяет статус MQTT соединения
     */
    fun isMqttConnected(): Boolean {
        return mqttClient?.isConnected == true
    }

    /**
     * Получает device_id из настроек или автоматически
     */
    private fun getDeviceId(): String {
        val savedDeviceId = databaseHelper.getSetting("device_id", "")
        return if (savedDeviceId.isEmpty()) {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } else {
            savedDeviceId
        }
    }

    /**
     * Получает device_name из настроек
     */
    private fun getDeviceName(): String {
        return databaseHelper.getSetting("device_name", android.os.Build.MODEL)
    }

    /**
     * Освобождает ресурсы
     */
    fun cleanup() {
        disconnectMqtt()
        scope.cancel()
        executor.shutdown()
    }
}
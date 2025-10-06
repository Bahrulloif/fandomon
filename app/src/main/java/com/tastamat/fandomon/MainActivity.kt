package com.tastamat.fandomon

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.utils.FandomatChecker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.widget.Switch
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.network.NetworkSender
import com.tastamat.fandomon.utils.FileLogger
import com.tastamat.fandomon.utils.FileWatcher
import com.tastamat.fandomon.utils.LogAnalyzer
import com.tastamat.fandomon.utils.FandomatLogMonitor

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var fandomatChecker: FandomatChecker
    private lateinit var networkSender: NetworkSender
    private lateinit var fileLogger: FileLogger
    private lateinit var fileWatcher: FileWatcher
    private lateinit var logAnalyzer: LogAnalyzer
    private lateinit var fandomatLogMonitor: FandomatLogMonitor

    private lateinit var fandomatStatusText: TextView
    private lateinit var fandomonStatusText: TextView
    private lateinit var internetStatusText: TextView
    private lateinit var lastEventText: TextView
    private lateinit var systemInfoText: TextView
    private lateinit var eventStatsText: TextView
    private lateinit var serviceSwitch: Switch


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeComponents()
        setupViews()

        // Автоматически запускаем сервис мониторинга при первом запуске
        ensureMonitoringServiceStarted()

        updateStatus()
    }

    /**
     * Убеждаемся что сервис мониторинга запущен
     */
    private fun ensureMonitoringServiceStarted() {
        if (!isServiceRunning(FandomonMonitoringService::class.java)) {
            Log.i(TAG, "Сервис не запущен, запускаем автоматически")
            startMonitoringService()
        } else {
            Log.d(TAG, "Сервис уже запущен")
        }
    }

    private fun initializeComponents() {
        databaseHelper = DatabaseHelper(this)
        fandomatChecker = FandomatChecker(this)
        networkSender = NetworkSender(this)
        fileLogger = FileLogger(this)
        logAnalyzer = LogAnalyzer(this)
        fandomatLogMonitor = FandomatLogMonitor(this)

        // Инициализируем FileWatcher
        initializeFileWatcher()

        // Запускаем мониторинг логов
        fandomatLogMonitor.startMonitoring()
    }

    private fun setupViews() {
        fandomatStatusText = findViewById(R.id.fandomatStatusText)
        fandomonStatusText = findViewById(R.id.fandomonStatusText)
        internetStatusText = findViewById(R.id.internetStatusText)
        lastEventText = findViewById(R.id.lastEventText)
        systemInfoText = findViewById(R.id.systemInfoText)
        eventStatsText = findViewById(R.id.eventStatsText)
        serviceSwitch = findViewById(R.id.serviceSwitch)

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            startMonitoringService()
        }

        findViewById<Button>(R.id.stopServiceButton).setOnClickListener {
            stopMonitoringService()
        }

        findViewById<Button>(R.id.viewLogsButton).setOnClickListener {
            openLogsActivity()
        }

        findViewById<Button>(R.id.fandomatLogsButton).setOnClickListener {
            openFandomatLogsActivity()
        }

        // Добавляем долгое нажатие для доступа к настройкам разрешений логов
        findViewById<Button>(R.id.fandomatLogsButton).setOnLongClickListener {
            openLogPermissionActivity()
            true
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            openSettingsActivity()
        }

        // Клик на статус Fandomat для запроса разрешения Usage Stats
        fandomatStatusText.setOnClickListener {
            if (!fandomatChecker.hasUsageStatsPermission()) {
                requestUsageStatsPermission()
            }
        }

        findViewById<Button>(R.id.testConnectionButton).setOnClickListener {
            testConnection()
        }

        // Добавляем долгое нажатие для создания тестовых событий
        findViewById<Button>(R.id.testConnectionButton).setOnLongClickListener {
            createTestEvents()
            true
        }

        findViewById<Button>(R.id.testConnectionButton).setOnClickListener {
            testConnection()
        }



        serviceSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            try {
                // Статус сервиса мониторинга
                val serviceRunning = isServiceRunning(FandomonMonitoringService::class.java)
                fandomonStatusText.text = if (serviceRunning) {
                    "🟢 Fandomon: Активен"
                } else {
                    "🔴 Fandomon: Остановлен"
                }
                serviceSwitch.isChecked = serviceRunning

                // Статус Fandomat
                val fandomatInstalled = fandomatChecker.isFandomatInstalled()
                val fandomatRunning = if (fandomatInstalled) fandomatChecker.isFandomatRunning() else false
                val fandomatVersion = fandomatChecker.getFandomatVersion()

                Log.d(TAG, "Fandomat статус: installed=$fandomatInstalled, running=$fandomatRunning, version=$fandomatVersion")

                fandomatStatusText.text = when {
                    !fandomatInstalled -> "❌ Fandomat: Не установлен"
                    fandomatRunning -> "🟢 Fandomat: Запущен${if (fandomatVersion != null) " (v$fandomatVersion)" else ""}"
                    else -> "🔴 Fandomat: Не запущен (требуется разрешение Usage Stats)"
                }

                // Статус интернета
                val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                val isConnected = networkCapabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

                internetStatusText.text = if (isConnected) {
                    "🟢 Интернет: Подключен"
                } else {
                    "🔴 Интернет: Отключен"
                }

                // Статистика событий
                val statistics = databaseHelper.getEventStatistics()
                eventStatsText.text = "📊 Всего событий: ${statistics.totalEvents} | Не отправлено: ${statistics.pendingEvents}"

                // Последнее событие
                val recentEvents = databaseHelper.getEventsBetween(
                    System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
                    System.currentTimeMillis()
                )

                if (recentEvents.isNotEmpty()) {
                    val lastEvent = recentEvents.first()
                    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val eventTime = timeFormat.format(Date(lastEvent.timestamp))

                    lastEventText.text = "📝 Последнее событие: $eventTime - ${lastEvent.type.description}"
                } else {
                    lastEventText.text = "📝 Последнее событие: нет данных"
                }

                // Системная информация
                val uptime = android.os.SystemClock.uptimeMillis() / 1000 / 60 // в минутах
                val batteryManager = getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)

                systemInfoText.text = "📊 Время работы: ${uptime}м | Батарея: ${batteryLevel}% | Android ${android.os.Build.VERSION.RELEASE}"

            } catch (e: Exception) {
                fandomonStatusText.text = "❌ Ошибка получения статуса"
            }
        }
    }

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(this, FandomonMonitoringService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            updateStatus()
        } catch (e: Exception) {
            fandomonStatusText.text = "❌ Ошибка запуска сервиса"
        }
    }

    private fun stopMonitoringService() {
        try {
            val serviceIntent = Intent(this, FandomonMonitoringService::class.java)
            stopService(serviceIntent)
            updateStatus()
        } catch (e: Exception) {
            fandomonStatusText.text = "❌ Ошибка остановки сервиса"
        }
    }

    private fun testConnection() {
        lifecycleScope.launch {
            try {
                systemInfoText.text = "🔍 Тестирование соединения..."

                val serverUrl = databaseHelper.getSetting("server_url")
                val mqttBroker = databaseHelper.getSetting("mqtt_broker")

                var results = mutableListOf<String>()

                // Тестируем REST API
                if (serverUrl.isNotEmpty()) {
                    networkSender.testConnection(serverUrl) { success, message ->
                        lifecycleScope.launch {
                            results.add("REST: ${if (success) "✅" else "❌"} $message")
                            updateTestResults(results)
                        }
                    }
                } else {
                    results.add("REST: ⚠️ URL сервера не настроен")
                }

                // Тестируем MQTT
                if (mqttBroker.isNotEmpty()) {
                    networkSender.testMqttConnection(mqttBroker) { success, message ->
                        lifecycleScope.launch {
                            results.add("MQTT: ${if (success) "✅" else "❌"} $message")
                            updateTestResults(results)
                        }
                    }
                } else {
                    results.add("MQTT: ⚠️ Брокер не настроен")
                    updateTestResults(results)
                }

            } catch (e: Exception) {
                systemInfoText.text = "❌ Ошибка теста соединения: ${e.message}"
            }
        }
    }


    private fun updateTestResults(results: List<String>) {
        val resultText = if (results.size >= 2) {
            "🔍 Тест завершен:\n${results.joinToString("\n")}"
        } else {
            "🔍 Тестирование... (${results.size}/2)"
        }
        systemInfoText.text = resultText

        // Возвращаем обычное отображение через 5 секунд
        if (results.size >= 2) {
            lifecycleScope.launch {
                kotlinx.coroutines.delay(5000)
                updateStatus()
            }
        }
    }

    private fun openLogsActivity() {
        val intent = Intent(this, LogsActivity::class.java)
        startActivity(intent)
    }

    private fun openFandomatLogsActivity() {
        val intent = Intent(this, FandomatLogsActivity::class.java)
        startActivity(intent)
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun openLogPermissionActivity() {
        val intent = Intent(this, LogPermissionActivity::class.java)
        startActivity(intent)
    }

    /**
     * Запрос разрешения Usage Stats
     */
    private fun requestUsageStatsPermission() {
        // Проверяем, есть ли уже разрешение
        if (fandomatChecker.hasUsageStatsPermission()) {
            Toast.makeText(this, "✅ Разрешение Usage Stats уже предоставлено", Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        }

        // Показываем диалог с объяснением
        AlertDialog.Builder(this)
            .setTitle("Требуется разрешение Usage Stats")
            .setMessage(
                "Для определения статуса приложения Fandomat необходимо разрешение Usage Stats.\n\n" +
                "Инструкция:\n" +
                "1. Нажмите 'Открыть настройки'\n" +
                "2. Найдите 'Fandomon' в списке\n" +
                "3. Включите переключатель\n" +
                "4. Вернитесь в приложение"
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    startActivity(intent)

                    Toast.makeText(
                        this,
                        "Найдите 'Fandomon' в списке и включите",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка открытия настроек Usage Stats", e)
                    Toast.makeText(
                        this,
                        "Ошибка открытия настроек. Откройте вручную:\nSettings → Apps → Special app access → Usage access",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Инструкция ADB") { _, _ ->
                showAdbInstructions()
            }
            .show()
    }

    /**
     * Показывает инструкцию для ADB
     */
    private fun showAdbInstructions() {
        val adbCommand = "adb shell appops set com.tastamat.fandomon GET_USAGE_STATS allow"

        AlertDialog.Builder(this)
            .setTitle("Предоставление через ADB")
            .setMessage(
                "Если у вас есть доступ к ADB, выполните команду:\n\n" +
                "$adbCommand\n\n" +
                "После выполнения команды нажмите 'Проверить'"
            )
            .setPositiveButton("Проверить") { _, _ ->
                if (fandomatChecker.hasUsageStatsPermission()) {
                    Toast.makeText(this, "✅ Разрешение предоставлено!", Toast.LENGTH_SHORT).show()
                    updateStatus()
                } else {
                    Toast.makeText(this, "❌ Разрешение не найдено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        checkUsageStatsPermission()
    }

    /**
     * Проверяет наличие разрешения Usage Stats при запуске
     */
    private fun checkUsageStatsPermission() {
        if (!fandomatChecker.hasUsageStatsPermission()) {
            Log.w(TAG, "Usage Stats разрешение не предоставлено")

            // Показываем hint только один раз за сессию
            val prefs = getSharedPreferences("fandomon_prefs", MODE_PRIVATE)
            val hintShown = prefs.getBoolean("usage_stats_hint_shown", false)

            if (!hintShown) {
                Toast.makeText(
                    this,
                    "💡 Нажмите на статус Fandomat для настройки разрешений",
                    Toast.LENGTH_LONG
                ).show()

                prefs.edit().putBoolean("usage_stats_hint_shown", true).apply()
            }
        } else {
            Log.d(TAG, "Usage Stats разрешение предоставлено")
        }
    }

    private fun createTestEvents() {
        lifecycleScope.launch {
            try {
                android.util.Log.d("MainActivity", "Создание тестовых событий...")

                val currentTime = System.currentTimeMillis()
                val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTime))

                databaseHelper.insertEvent(
                    EventType.FANDOMON_STARTED,
                    "Тестовое событие создано в $timeText",
                    EventSeverity.INFO
                )

                databaseHelper.insertEvent(
                    EventType.FANDOMAT_RESTARTED,
                    "Fandomat автоматически перезапущен в $timeText",
                    EventSeverity.WARNING
                )

                databaseHelper.insertEvent(
                    EventType.NETWORK_RESTORED,
                    "Интернет соединение восстановлено в $timeText",
                    EventSeverity.INFO
                )

                databaseHelper.insertEvent(
                    EventType.POWER_CONNECTED,
                    "Устройство подключено к питанию в $timeText",
                    EventSeverity.INFO
                )

                databaseHelper.insertEvent(
                    EventType.FANDOMON_ERROR,
                    "Тестовая ошибка Fandomon в $timeText",
                    EventSeverity.ERROR
                )

                val statistics = databaseHelper.getEventStatistics()
                android.util.Log.d("MainActivity", "Создано событий. Всего в БД: ${statistics.totalEvents}, неотправленных: ${statistics.pendingEvents}")

                // Обновляем статус на главном экране
                updateStatus()

                // Показываем Toast
                android.widget.Toast.makeText(this@MainActivity, "✅ Создано 5 тестовых событий", android.widget.Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Ошибка создания тестовых событий", e)
                android.widget.Toast.makeText(this@MainActivity, "❌ Ошибка создания событий: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initializeFileWatcher() {
        try {
            val logFilePath = fileLogger.getLogFilePath()
            android.util.Log.d("MainActivity", "Инициализация FileWatcher для файла: $logFilePath")

            fileWatcher = FileWatcher(
                context = this,
                filePath = logFilePath,
                onFileChanged = { newLines ->
                    android.util.Log.d("MainActivity", "Получено ${newLines.size} новых строк логов")
                    logAnalyzer.analyzeLogLines(newLines)
                },
                onFileInactive = {
                    android.util.Log.w("MainActivity", "Обнаружена неактивность файла логов")
                    logAnalyzer.handleLogInactivity()
                }
            )

            fileWatcher.startWatching()
            fileLogger.writeSystemEvent("FILE_WATCHER_STARTED", "Мониторинг файла логов запущен: $logFilePath")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Ошибка инициализации FileWatcher", e)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::fileWatcher.isInitialized) {
                fileWatcher.stopWatching()
            }
            if (::logAnalyzer.isInitialized) {
                logAnalyzer.cleanup()
            }
            if (::fandomatLogMonitor.isInitialized) {
                fandomatLogMonitor.cleanup()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Ошибка очистки ресурсов", e)
        }
    }
}
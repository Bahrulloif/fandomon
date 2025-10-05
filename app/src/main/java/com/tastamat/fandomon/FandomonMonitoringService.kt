package com.tastamat.fandomon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.network.NetworkSender
import com.tastamat.fandomon.utils.FandomatChecker
import com.tastamat.fandomon.utils.LogMonitor
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class FandomonMonitoringService : Service() {

    companion object {
        private const val TAG = "FandomonService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "FandomonMonitoring"
        private const val FANDOMAT_PACKAGE = "com.tastamat.fandomat"
        private const val CHECK_INTERVAL = 30L // seconds
        private const val STATUS_SEND_INTERVAL = 300L // 5 minutes
    }

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var networkSender: NetworkSender
    private lateinit var fandomatChecker: FandomatChecker
    private lateinit var logMonitor: LogMonitor

    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var handler: Handler
    private lateinit var powerManager: PowerManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private var isNetworkConnected = false
    private var lastFandomatStatus = false
    private var lastLogCheck = System.currentTimeMillis()

    // Network callback для мониторинга сети
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (!isNetworkConnected) {
                isNetworkConnected = true
                logEvent(EventType.NETWORK_RESTORED, "Сеть восстановлена")
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            if (isNetworkConnected) {
                isNetworkConnected = false
                logEvent(EventType.NETWORK_DISCONNECTED, "Потеря сетевого соединения")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Сервис мониторинга создан")

        initializeComponents()
        createNotificationChannel()
        registerNetworkCallback()
        startMonitoring()
    }

    private fun initializeComponents() {
        databaseHelper = DatabaseHelper(this)
        networkSender = NetworkSender(this)
        fandomatChecker = FandomatChecker(this)
        logMonitor = LogMonitor(this)

        scheduler = Executors.newScheduledThreadPool(3)
        handler = Handler(Looper.getMainLooper())
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fandomon::MonitoringWakeLock")

        // Проверяем начальное состояние сети
        checkNetworkStatus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fandomon Мониторинг",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянный мониторинг Fandomat приложения"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка регистрации network callback", e)
        }
    }

    private fun startMonitoring() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10*60*1000L) // 10 минут
        }

        // Основной цикл проверки Fandomat
        scheduler.scheduleWithFixedDelay({
            try {
                Log.d(TAG, "Старт мониторинга")
                checkFandomatStatus()
                checkFandomatLogs()
                checkPowerStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в цикле мониторинга", e)
            }
        }, 0, CHECK_INTERVAL, TimeUnit.SECONDS)

        // Отправка статуса на сервер
        scheduler.scheduleWithFixedDelay({
            try {
                sendStatusToServer()
                sendPendingEvents()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки статуса", e)
            }
        }, STATUS_SEND_INTERVAL, STATUS_SEND_INTERVAL, TimeUnit.SECONDS)

        logEvent(EventType.FANDOMON_STARTED, "Сервис мониторинга запущен")
    }

    private fun checkFandomatStatus() {
        val isRunning = fandomatChecker.isFandomatRunning()

        if (isRunning != lastFandomatStatus) {
            if (!isRunning) {
                logEvent(EventType.FANDOMAT_CRASHED, "Fandomat приложение не запущено")

                // Пытаемся перезапустить Fandomat
                if (fandomatChecker.startFandomat()) {
                    logEvent(EventType.FANDOMAT_RESTARTED, "Fandomat перезапущен автоматически")
                } else {
                    logEvent(EventType.FANDOMAT_START_FAILED, "Не удалось запустить Fandomat")
                }
            } else {
                logEvent(EventType.FANDOMAT_RESTORED, "Fandomat приложение запущено")
            }

            lastFandomatStatus = isRunning
        }
    }

    private fun checkFandomatLogs() {
        val currentTime = System.currentTimeMillis()
        val logsSince = logMonitor.getLogsSince(FANDOMAT_PACKAGE, lastLogCheck)

        Log.d(TAG, "checkFandomatLogs: " + logsSince)

        if (logsSince.isEmpty() && (currentTime - lastLogCheck) > TimeUnit.MINUTES.toMillis(5)) {
            // Если логов не было более 5 минут
            logEvent(EventType.LOGS_MISSING, "Логи Fandomat отсутствуют более 5 минут")
        }

        // Проверяем на ошибки в логах
        logsSince.forEach { logEntry ->
            if (logEntry.contains("FATAL", true) || logEntry.contains("ERROR", true)) {
                logEvent(EventType.FANDOMAT_ERROR, "Ошибка в логах Fandomat: $logEntry")
            }
        }

        lastLogCheck = currentTime
    }

    private fun checkPowerStatus() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Проверяем уровень батареи
        if (batteryLevel < 15 && !isCharging) {
            logEvent(EventType.POWER_LOW, "Низкий заряд батареи: $batteryLevel%")
        }

        // Логируем статус питания в базу для отслеживания
        databaseHelper.updatePowerStatus(isCharging, batteryLevel)
    }

    private fun checkNetworkStatus() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        isNetworkConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun sendStatusToServer() {
        val status = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "fandomat_running" to lastFandomatStatus,
            "network_connected" to isNetworkConnected,
            "fandomon_version" to BuildConfig.VERSION_NAME,
            "device_id" to android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        )

        networkSender.sendStatus(status)
    }

    private fun sendPendingEvents() {
        val pendingEvents = databaseHelper.getPendingEvents()
        pendingEvents.forEach { event ->
            networkSender.sendEvent(event) { success ->
                if (success) {
                    databaseHelper.markEventAsSent(event.id)
                }
            }
        }
    }

    private fun logEvent(type: EventType, details: String) {
        Log.i(TAG, "$type: $details")
        databaseHelper.insertEvent(type, details)

        // Обновляем уведомление с последним событием
        updateNotification("$type: $details")
    }

    private fun updateNotification(lastEvent: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTime = timeFormat.format(Date())

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fandomon Мониторинг")
            .setContentText("Активен с $currentTime")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Статус: ${if (lastFandomatStatus) "✓" else "✗"} Fandomat, ${if (isNetworkConnected) "✓" else "✗"} Сеть\nПоследнее: $lastEvent"))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setAutoCancel(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Сервис получил команду")

        // Создаем начальное уведомление
        updateNotification("Сервис запущен")

        return START_STICKY // Автоматически перезапускается при убийстве системой
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Сервис мониторинга остановлен")

        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отмены network callback", e)
        }

        scheduler.shutdown()

        if (wakeLock.isHeld) {
            wakeLock.release()
        }

        logEvent(EventType.FANDOMON_STOPPED, "Сервис мониторинга остановлен")
    }
}
package com.tastamat.fandomon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.utils.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.*

/**
 * Облегченный сервис мониторинга с использованием AlarmManager
 * Не выполняет постоянные проверки, а только управляет алярмами и мониторит сеть
 */
class FandomonMonitoringService : Service() {

    companion object {
        private const val TAG = "FandomonService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "FandomonMonitoring"
    }

    private lateinit var databaseHelper: DatabaseHelper
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var handler: Handler

    private var isNetworkConnected = false

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
        Log.d(TAG, "Облегченный сервис мониторинга создан")

        initializeComponents()
        createNotificationChannel()
        registerNetworkCallback()
        startAlarmBasedMonitoring()
    }

    private fun initializeComponents() {
        databaseHelper = DatabaseHelper(this)
        alarmScheduler = AlarmScheduler(this)
        handler = Handler(Looper.getMainLooper())

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

    /**
     * Запускает мониторинг через AlarmManager вместо постоянных циклов
     * Значительно снижает нагрузку на CPU
     */
    private fun startAlarmBasedMonitoring() {
        // Проверяем, может ли приложение использовать точные алярмы
        if (!alarmScheduler.canScheduleExactAlarms()) {
            Log.w(TAG, "Приложение не может планировать точные алярмы (Android 12+)")
        }

        // Запускаем все периодические задачи через AlarmManager
        alarmScheduler.scheduleAllMonitoring()

        logEvent(EventType.FANDOMON_STARTED, "Сервис мониторинга запущен (AlarmManager режим)")
        Log.i(TAG, "Мониторинг через AlarmManager активирован - низкая нагрузка на CPU")
    }

    private fun checkNetworkStatus() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        isNetworkConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
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
            .setContentTitle("Fandomon Мониторинг (Режим энергосбережения)")
            .setContentText("AlarmManager активен")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Режим: AlarmManager (низкая нагрузка)\nСеть: ${if (isNetworkConnected) "✓" else "✗"}\nПоследнее: $lastEvent"))
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

        // Отменяем все запланированные алярмы
        alarmScheduler.cancelAllMonitoring()

        logEvent(EventType.FANDOMON_STOPPED, "Сервис мониторинга остановлен")
    }
}
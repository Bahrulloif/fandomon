package com.tastamat.fandomon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.network.NetworkSender
import com.tastamat.fandomon.utils.AlarmScheduler
import com.tastamat.fandomon.utils.FandomatChecker
import com.tastamat.fandomon.utils.FileLogger
import com.tastamat.fandomon.utils.LogMonitor
import kotlinx.coroutines.*

/**
 * BroadcastReceiver для обработки периодических задач мониторинга
 * Срабатывает по алярмам от AlarmManager
 */
class MonitoringAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MonitoringAlarmReceiver"
        private const val WAKELOCK_TIMEOUT = 60_000L // 1 минута максимум
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Получен алярм: $action")

        // Используем WakeLock чтобы устройство не заснуло во время выполнения
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Fandomon::AlarmWakeLock"
        )

        // Используем goAsync() для длительных операций
        val pendingResult = goAsync()

        wakeLock.acquire(WAKELOCK_TIMEOUT)

        // Выполняем задачу в корутине
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    AlarmScheduler.ACTION_MONITOR_FANDOMAT -> handleMonitoring(context)
                    AlarmScheduler.ACTION_SEND_STATUS -> handleStatusSending(context)
                    AlarmScheduler.ACTION_CHECK_LOGS -> handleLogChecking(context)
                    else -> Log.w(TAG, "Неизвестное действие: $action")
                }

                // Перепланируем следующий алярм (для Android 12+ где нет автоповтора)
                val scheduler = AlarmScheduler(context)
                scheduler.rescheduleAlarm(action)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка выполнения задачи $action", e)
            } finally {
                // Освобождаем WakeLock и завершаем broadcast
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
                pendingResult.finish()
            }
        }
    }

    /**
     * Обрабатывает мониторинг Fandomat
     */
    private suspend fun handleMonitoring(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val databaseHelper = DatabaseHelper(context)
                val fandomatChecker = FandomatChecker(context)
                val fileLogger = FileLogger(context)

                Log.d(TAG, "Начало проверки Fandomat")

                val isRunning = fandomatChecker.isFandomatRunning()
                val wasRunning = getLastFandomatStatus(context)

                // Проверяем изменение статуса
                if (isRunning != wasRunning) {
                    if (!isRunning) {
                        Log.w(TAG, "Fandomat не запущен!")

                        databaseHelper.insertEvent(
                            type = EventType.FANDOMAT_CRASHED,
                            details = "Fandomat приложение не запущено",
                            severity = EventSeverity.CRITICAL
                        )
                        fileLogger.writeError(TAG, "Fandomat не запущен, попытка перезапуска")

                        // Пытаемся перезапустить
                        delay(2000) // Небольшая задержка перед рестартом
                        if (fandomatChecker.startFandomat()) {
                            databaseHelper.insertEvent(
                                type = EventType.FANDOMAT_RESTARTED,
                                details = "Fandomat перезапущен автоматически",
                                severity = EventSeverity.WARNING
                            )
                            fileLogger.writeInfo(TAG, "Fandomat успешно перезапущен")
                        } else {
                            databaseHelper.insertEvent(
                                type = EventType.FANDOMAT_START_FAILED,
                                details = "Не удалось запустить Fandomat",
                                severity = EventSeverity.CRITICAL
                            )
                            fileLogger.writeError(TAG, "Не удалось перезапустить Fandomat")
                        }
                    } else {
                        Log.i(TAG, "Fandomat запущен")
                        databaseHelper.insertEvent(
                            type = EventType.FANDOMAT_RESTORED,
                            details = "Fandomat приложение работает",
                            severity = EventSeverity.INFO
                        )
                    }

                    // Сохраняем новый статус
                    saveLastFandomatStatus(context, isRunning)
                }

                // Проверяем статус батареи
                checkPowerStatus(context, databaseHelper)

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка мониторинга", e)
            }
        }
    }

    /**
     * Обрабатывает отправку статуса на сервер
     */
    private suspend fun handleStatusSending(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val databaseHelper = DatabaseHelper(context)
                val networkSender = NetworkSender(context)
                val fandomatChecker = FandomatChecker(context)

                Log.d(TAG, "Отправка статуса на сервер")

                // Формируем статус
                val status = mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "fandomat_running" to fandomatChecker.isFandomatRunning(),
                    "fandomat_version" to fandomatChecker.getFandomatVersion(),
                    "fandomon_version" to BuildConfig.VERSION_NAME,
                    "device_id" to getDeviceId(context),
                    "device_name" to databaseHelper.getSetting("device_name", android.os.Build.MODEL),
                    "battery_level" to getBatteryLevel(context)
                )

                networkSender.sendStatus(status)

                // Отправляем неотправленные события
                val pendingEvents = databaseHelper.getPendingEvents()
                if (pendingEvents.isNotEmpty()) {
                    Log.d(TAG, "Отправка ${pendingEvents.size} неотправленных событий")
                    networkSender.sendEventBatch(pendingEvents) { successCount ->
                        Log.i(TAG, "Отправл��но $successCount из ${pendingEvents.size} событий")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки статуса", e)
            }
        }
    }

    /**
     * Обрабатывает проверку логов
     */
    private suspend fun handleLogChecking(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val databaseHelper = DatabaseHelper(context)
                val logMonitor = LogMonitor(context)

                Log.d(TAG, "Проверка логов Fandomat")

                val lastCheck = getLastLogCheckTime(context)
                val logs = logMonitor.getLogsSince("com.tastamat.fandomat", lastCheck)

                if (logs.isEmpty()) {
                    val timeSinceLastLog = System.currentTimeMillis() - lastCheck
                    if (timeSinceLastLog > 5 * 60 * 1000) { // 5 минут
                        Log.w(TAG, "Логи отсутствуют более 5 минут")
                        databaseHelper.insertEvent(
                            type = EventType.LOGS_MISSING,
                            details = "Логи Fandomat отсутствуют более 5 минут",
                            severity = EventSeverity.WARNING
                        )
                    }
                } else {
                    // Проверяем на ошибки
                    logs.forEach { logEntry ->
                        if (logEntry.contains("FATAL", ignoreCase = true) ||
                            logEntry.contains("ERROR", ignoreCase = true) ||
                            logEntry.contains("CRASH", ignoreCase = true)
                        ) {
                            databaseHelper.insertEvent(
                                type = EventType.FANDOMAT_ERROR,
                                details = "Критическая ошибка в логах: ${logEntry.take(200)}",
                                severity = EventSeverity.ERROR
                            )
                        }
                    }
                }

                // Сохраняем время последней проверки
                saveLastLogCheckTime(context, System.currentTimeMillis())

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка проверки логов", e)
            }
        }
    }

    /**
     * Проверяет статус питания
     */
    private fun checkPowerStatus(context: Context, databaseHelper: DatabaseHelper) {
        try {
            val batteryLevel = getBatteryLevel(context)
            val isCharging = isCharging(context)

            // Обновляем статус в БД
            databaseHelper.updatePowerStatus(isCharging, batteryLevel)

            // Предупреждение о низком заряде
            if (batteryLevel < 15 && !isCharging) {
                databaseHelper.insertEvent(
                    type = EventType.POWER_LOW,
                    details = "Низкий заряд батареи: $batteryLevel%",
                    severity = EventSeverity.WARNING
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки питания", e)
        }
    }

    // ========== Helper методы ==========

    private fun getDeviceId(context: Context): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    private fun getBatteryLevel(context: Context): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.isCharging
    }

    // Сохранение/загрузка статуса в SharedPreferences (легковесная альтернатива БД)
    private fun getLastFandomatStatus(context: Context): Boolean {
        val prefs = context.getSharedPreferences("fandomon_monitoring", Context.MODE_PRIVATE)
        return prefs.getBoolean("last_fandomat_status", false)
    }

    private fun saveLastFandomatStatus(context: Context, isRunning: Boolean) {
        val prefs = context.getSharedPreferences("fandomon_monitoring", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("last_fandomat_status", isRunning).apply()
    }

    private fun getLastLogCheckTime(context: Context): Long {
        val prefs = context.getSharedPreferences("fandomon_monitoring", Context.MODE_PRIVATE)
        return prefs.getLong("last_log_check", System.currentTimeMillis() - 60_000L)
    }

    private fun saveLastLogCheckTime(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences("fandomon_monitoring", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_log_check", timestamp).apply()
    }
}

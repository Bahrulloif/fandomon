package com.tastamat.fandomon.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tastamat.fandomon.MonitoringAlarmReceiver

/**
 * Управляет периодическими задачами мониторинга через AlarmManager
 * Снижает нагрузку на CPU по сравнению с постоянно работающими ScheduledExecutor
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        private const val TAG = "AlarmScheduler"

        // Request codes для разных типов алармов
        const val REQUEST_CODE_MONITORING = 1001
        const val REQUEST_CODE_STATUS_SEND = 1002
        const val REQUEST_CODE_LOG_CHECK = 1003

        // Actions для BroadcastReceiver
        const val ACTION_MONITOR_FANDOMAT = "com.tastamat.fandomon.ACTION_MONITOR_FANDOMAT"
        const val ACTION_SEND_STATUS = "com.tastamat.fandomon.ACTION_SEND_STATUS"
        const val ACTION_CHECK_LOGS = "com.tastamat.fandomon.ACTION_CHECK_LOGS"

        // Интервалы (в миллисекундах)
        const val MONITORING_INTERVAL = 30_000L // 30 секунд
        const val STATUS_SEND_INTERVAL = 300_000L // 5 минут
        const val LOG_CHECK_INTERVAL = 60_000L // 1 минута
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Запускает все периодические задачи мониторинга
     */
    fun scheduleAllMonitoring() {
        scheduleMonitoring()
        scheduleStatusSending()
        scheduleLogChecking()
        Log.i(TAG, "Все задачи мониторинга запланированы")
    }

    /**
     * Планирует основную проверку Fandomat
     */
    fun scheduleMonitoring() {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java).apply {
            action = ACTION_MONITOR_FANDOMAT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MONITORING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Используем setRepeating для периодических задач
        // Для точности используем setExactAndAllowWhileIdle на каждом срабатывании
        scheduleExactRepeating(
            pendingIntent,
            MONITORING_INTERVAL,
            "Мониторинг Fandomat"
        )
    }

    /**
     * Планирует отправку статуса на сервер
     */
    fun scheduleStatusSending() {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java).apply {
            action = ACTION_SEND_STATUS
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_STATUS_SEND,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactRepeating(
            pendingIntent,
            STATUS_SEND_INTERVAL,
            "Отправка статуса"
        )
    }

    /**
     * Планирует проверку логов
     */
    fun scheduleLogChecking() {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java).apply {
            action = ACTION_CHECK_LOGS
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_LOG_CHECK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleExactRepeating(
            pendingIntent,
            LOG_CHECK_INTERVAL,
            "Проверка логов"
        )
    }

    /**
     * Планирует точное повторяющееся выполнение
     * Использует API в зависимости от версии Android
     */
    private fun scheduleExactRepeating(
        pendingIntent: PendingIntent,
        intervalMillis: Long,
        taskName: String
    ) {
        val triggerTime = System.currentTimeMillis() + intervalMillis

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    // Android 12+ - требуется разрешение SCHEDULE_EXACT_ALARM
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.d(TAG, "$taskName запланирован (exact, Android 12+)")
                    } else {
                        // Fallback на неточный алярм
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.w(TAG, "$taskName запланирован (неточный, нет разрешения)")
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    // Android 6.0+ - используем setExactAndAllowWhileIdle для работы в Doze
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "$taskName запланирован (exact, Android 6+)")
                }
                else -> {
                    // Старые версии Android
                    alarmManager.setRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        intervalMillis,
                        pendingIntent
                    )
                    Log.d(TAG, "$taskName запланирован (repeating, старый Android)")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет разрешения для планирования $taskName", e)
            // Fallback на неточный алярм
            fallbackToInexactAlarm(pendingIntent, triggerTime, taskName)
        }
    }

    /**
     * Запасной вариант - неточный алярм
     */
    private fun fallbackToInexactAlarm(
        pendingIntent: PendingIntent,
        triggerTime: Long,
        taskName: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        Log.w(TAG, "$taskName запланирован (неточный fallback)")
    }

    /**
     * Перепланирует алярм (вызывается после его срабатывания)
     */
    fun rescheduleAlarm(action: String) {
        when (action) {
            ACTION_MONITOR_FANDOMAT -> scheduleMonitoring()
            ACTION_SEND_STATUS -> scheduleStatusSending()
            ACTION_CHECK_LOGS -> scheduleLogChecking()
            else -> Log.w(TAG, "Неизвестное действие для перепланирования: $action")
        }
    }

    /**
     * Отменяет все запланированные задачи
     */
    fun cancelAllMonitoring() {
        cancelAlarm(REQUEST_CODE_MONITORING, ACTION_MONITOR_FANDOMAT)
        cancelAlarm(REQUEST_CODE_STATUS_SEND, ACTION_SEND_STATUS)
        cancelAlarm(REQUEST_CODE_LOG_CHECK, ACTION_CHECK_LOGS)
        Log.i(TAG, "Все задачи мониторинга отменены")
    }

    /**
     * Отменяет конкретный алярм
     */
    private fun cancelAlarm(requestCode: Int, action: String) {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java).apply {
            this.action = action
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        Log.d(TAG, "Алярм $action отменен")
    }

    /**
     * Планирует одноразовую проверку с задержкой
     */
    fun scheduleOneTimeCheck(delayMillis: Long) {
        val intent = Intent(context, MonitoringAlarmReceiver::class.java).apply {
            action = ACTION_MONITOR_FANDOMAT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_MONITORING + 1000, // Другой request code
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + delayMillis

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        Log.d(TAG, "Запланирована одноразовая проверка через ${delayMillis}ms")
    }

    /**
     * Проверяет, может ли приложение планировать точные алярмы (Android 12+)
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // На старых версиях всегда доступно
        }
    }
}

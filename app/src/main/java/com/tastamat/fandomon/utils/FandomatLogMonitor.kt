package com.tastamat.fandomon.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.network.NetworkSender
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class FandomatLogMonitor(private val context: Context) {

    companion object {
        private const val TAG = "FandomatLogMonitor"
        private const val CHECK_INTERVAL = 30 * 1000L // 30 секунд
    }

    private val fileLogger = FileLogger(context)
    private val databaseHelper = DatabaseHelper(context)
    private val networkSender = NetworkSender(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    private var lastLogTime = 0L
    private var lastSentLogTime = 0L // Время последнего отправленного лога

    /**
     * Запускает мониторинг логов
     */
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) {
            Log.w(TAG, "Мониторинг уже запущен")
            return
        }

        Log.i(TAG, "Запуск мониторинга логов Fandomat")
        fileLogger.writeSystemEvent("LOG_MONITOR_START", "Запуск мониторинга файла логов")

        monitoringJob = scope.launch {
            while (isActive) {
                try {
                    checkLogs()
                    delay(CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в цикле мониторинга", e)
                    delay(CHECK_INTERVAL)
                }
            }
        }
    }

    /**
     * Останавливает мониторинг логов
     */
    fun stopMonitoring() {
        Log.i(TAG, "Остановка мониторинга логов")
        monitoringJob?.cancel()
        monitoringJob = null
        fileLogger.writeSystemEvent("LOG_MONITOR_STOP", "Остановка мониторинга файла логов")
    }

    /**
     * Проверяет логи и выполняет необходимые действия
     */
    private suspend fun checkLogs() {
        try {
            val lastLogEntry = getLastLogEntry()

            if (lastLogEntry != null) {
                val logTime = lastLogEntry.timestamp
                val currentTime = System.currentTimeMillis()
                val timeSinceLastLog = currentTime - logTime

                Log.d(TAG, "Последний лог: ${Date(logTime)}, прошло времени: ${timeSinceLastLog / 1000}с")

                // Получаем настройку времени неактивности (в минутах) и конвертируем в миллисекунды
                val inactivityTimeoutMinutes = databaseHelper.getSetting("inactivity_timeout_minutes", "5").toIntOrNull() ?: 5
                val inactivityTimeoutMs = inactivityTimeoutMinutes * 60 * 1000L

                // Проверяем на неактивность
                if (timeSinceLastLog > inactivityTimeoutMs) {
                    // Отправляем последний лог по MQTT только при обнаружении неактивности
                    // И только если этот лог еще не был отправлен
                    if (logTime > lastSentLogTime) {
                        Log.i(TAG, "Отправляем последний лог перед неактивностью по MQTT")
                        sendLogToMqtt(lastLogEntry)
                        lastSentLogTime = logTime
                    }

                    handleInactivity(timeSinceLastLog)
                } else {
                    lastLogTime = logTime
                }

            } else {
                Log.w(TAG, "Не найдено логов в файле")

                // Если нет логов совсем, тоже считаем это неактивностью
                val currentTime = System.currentTimeMillis()
                val inactivityTimeoutMinutes = databaseHelper.getSetting("inactivity_timeout_minutes", "5").toIntOrNull() ?: 5
                val inactivityTimeoutMs = inactivityTimeoutMinutes * 60 * 1000L

                if (lastLogTime > 0 && (currentTime - lastLogTime) > inactivityTimeoutMs) {
                    handleInactivity(currentTime - lastLogTime)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки логов", e)
        }
    }

    /**
     * Получает последнюю запись лога из файла
     */
    private fun getLastLogEntry(): LogEntry? {
        try {
            if (!fileLogger.logFileExists()) {
                Log.d(TAG, "Файл логов не существует")
                return null
            }

            val lastLines = fileLogger.readLastLogs(10) // Читаем последние 10 строк
            if (lastLines.isEmpty()) {
                Log.d(TAG, "Файл логов пуст")
                return null
            }

            // Ищем последнюю валидную запись лога
            for (line in lastLines.reversed()) {
                val logEntry = parseLogLine(line)
                if (logEntry != null) {
                    return logEntry
                }
            }

            Log.w(TAG, "Не найдено валидных записей логов")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения последнего лога", e)
            return null
        }
    }

    /**
     * Парсит строку лога
     */
    private fun parseLogLine(line: String): LogEntry? {
        try {
            // Формат: "yyyy-MM-dd HH:mm:ss.SSS [LEVEL] TAG: MESSAGE"
            val pattern = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(\w+)\] ([^:]+): (.*)""")
            val match = pattern.find(line)

            return if (match != null) {
                val timestampStr = match.groupValues[1]
                val level = match.groupValues[2]
                val tag = match.groupValues[3]
                val message = match.groupValues[4]

                val timestamp = try {
                    dateFormat.parse(timestampStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }

                LogEntry(
                    timestamp = timestamp,
                    level = level,
                    tag = tag,
                    message = message,
                    rawLine = line
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка парсинга строки лога: $line", e)
            return null
        }
    }

    /**
     * Отправляет лог по MQTT (только последний лог перед неактивностью)
     */
    private suspend fun sendLogToMqtt(logEntry: LogEntry) {
        try {
            val logJson = JSONObject().apply {
                put("timestamp", logEntry.timestamp)
                put("level", logEntry.level)
                put("tag", logEntry.tag)
                put("message", logEntry.message)
                put("source", "fandomat_last_log_before_inactivity")
                put("device_id", getDeviceId())
                put("device_name", getDeviceName())
                put("log_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(logEntry.timestamp)))
                put("inactivity_detected", true)
                put("description", "Последний лог перед неактивностью Fandomat (>5 мин)")
            }

            // Создаем событие для отправки
            val event = com.tastamat.fandomon.data.Event(
                type = EventType.LOGS_MISSING, // Используем как тип для логов
                details = logJson.toString(),
                severity = when (logEntry.level.uppercase()) {
                    "ERROR", "FATAL" -> EventSeverity.ERROR
                    "WARN" -> EventSeverity.WARNING
                    else -> EventSeverity.INFO
                }
            )

            networkSender.sendEvent(event)
            Log.i(TAG, "Последний лог перед неактивностью отправлен по MQTT: ${logEntry.level} - ${logEntry.message}")
            fileLogger.writeSystemEvent("LAST_LOG_SENT_MQTT", "Последний лог перед неактивностью отправлен по MQTT: ${logEntry.level} - ${logEntry.message}")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки лога по MQTT", e)
            fileLogger.writeError("FandomatLogMonitor", "Ошибка отправки последнего лога по MQTT: ${e.message}", e)
        }
    }

    /**
     * Обрабатывает неактивность логов
     */
    private suspend fun handleInactivity(inactivityDuration: Long) {
        val inactivityMinutes = inactivityDuration / 60000
        Log.w(TAG, "Обнаружена неактивность логов: ${inactivityMinutes} минут")

        fileLogger.writeSystemEvent("FANDOMAT_INACTIVITY_DETECTED",
            "Неактивность логов Fandomat: ${inactivityMinutes} минут")

        // Записываем событие в БД
        databaseHelper.insertEvent(
            type = EventType.FANDOMAT_CRASHED,
            details = "Неактивность логов Fandomat: ${inactivityMinutes} минут. Планируется перезапуск.",
            severity = EventSeverity.WARNING
        )

        // Пытаемся перезапустить Fandomat
        restartFandomat()
    }

    /**
     * Перезапускает приложение Fandomat
     */
    private suspend fun restartFandomat() {
        try {
            Log.i(TAG, "Попытка перезапуска Fandomat")
            fileLogger.writeSystemEvent("FANDOMAT_RESTART_ATTEMPT", "Попытка автоперезапуска Fandomat")

            // Записываем событие перезапуска
            databaseHelper.insertEvent(
                type = EventType.FANDOMAT_RESTARTED,
                details = "Автоматический перезапуск Fandomat из-за неактивности логов",
                severity = EventSeverity.WARNING
            )

            // Получаем Intent для запуска Fandomat
            val intent = context.packageManager.getLaunchIntentForPackage("com.tastamat.fandomat")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                fileLogger.writeSystemEvent("FANDOMAT_RESTART_SUCCESS", "Команда перезапуска Fandomat выполнена")
                Log.i(TAG, "Команда перезапуска Fandomat отправлена")

                // Обновляем время последнего лога, чтобы избежать повторных перезапусков
                lastLogTime = System.currentTimeMillis()

            } else {
                fileLogger.writeError("FandomatLogMonitor", "Не удалось найти Fandomat для запуска")
                Log.e(TAG, "Не удалось найти Fandomat для запуска")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перезапуска Fandomat", e)
            fileLogger.writeError("FandomatLogMonitor", "Ошибка перезапуска Fandomat: ${e.message}", e)
        }
    }

    /**
     * Получает device_id из настроек
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
        stopMonitoring()
        scope.cancel()
    }

    /**
     * Получает статистику мониторинга
     */
    fun getMonitoringStats(): Map<String, Any> {
        return mapOf(
            "isActive" to (monitoringJob?.isActive == true),
            "lastLogTime" to lastLogTime,
            "fileExists" to fileLogger.logFileExists(),
            "fileSize" to fileLogger.getLogFileSize(),
            "filePath" to fileLogger.getLogFilePath()
        )
    }

    /**
     * Класс для представления записи лога
     */
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val rawLine: String
    )
}
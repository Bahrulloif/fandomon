package com.tastamat.fandomon.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.network.NetworkSender
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LogAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "LogAnalyzer"
        private const val RESTART_DELAY = 10_000L // 10 секунд
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val RESTART_COOLDOWN = 300_000L // 5 минут
        private const val BATCH_SIZE = 50 // размер пакета для БД операций
        private const val BATCH_TIMEOUT = 5_000L // таймаут пакета в мс
        private const val THROTTLE_DELAY = 100L // задержка throttling в мс
        private const val PROCESS_CHECK_COOLDOWN = 30_000L // проверка процесса каждые 30 сек
    }

    private val databaseHelper = DatabaseHelper(context)
    private val networkSender = NetworkSender(context)
    private val fileLogger = FileLogger(context)

    private var lastRestartAttempt = 0L
    private var restartAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Компилированный regex паттерн (вместо кеширования результатов)
    private val logPattern = Regex("""(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(\w+)\] ([^:]+): (.*)""")
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // Пакетная обработка БД с backpressure
    private val databaseBatch = mutableListOf<LogEntry>()
    private val batchChannel = Channel<LogEntry>(capacity = 1000)
    private var lastBatchTime = System.currentTimeMillis()
    private var isBatchProcessorActive = true

    // Throttling
    private val lastProcessTime = AtomicLong(0)

    // Кеширование проверки процесса
    private var lastProcessCheck = 0L
    private var cachedProcessState = false

    init {
        // Инициализируем пакетную обработку БД
        startBatchProcessor()
    }

    /**
     * Анализирует новые строки логов с throttling
     */
    fun analyzeLogLines(lines: List<String>) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastProcessTime.get()

        // Throttling - пропускаем если слишком часто
        if (currentTime - lastTime < THROTTLE_DELAY) {
            return
        }

        if (lastProcessTime.compareAndSet(lastTime, currentTime)) {
            scope.launch {
                lines.forEach { line ->
                    analyzeLogLine(line)
                }
            }
        }
    }

    /**
     * Запускает пакетный процессор для БД операций
     * Оптимизирован: добавлена проверка isActive и обработка ошибок
     */
    private fun startBatchProcessor() {
        scope.launch {
            while (isActive && isBatchProcessorActive) {
                try {
                    val batch = mutableListOf<LogEntry>()
                    var timeoutReached = false

                    // Собираем пакет
                    while (batch.size < BATCH_SIZE && !timeoutReached && isActive) {
                        val entry = withTimeoutOrNull(BATCH_TIMEOUT) {
                            batchChannel.receive()
                        }

                        if (entry != null) {
                            batch.add(entry)
                        } else {
                            timeoutReached = true
                        }
                    }

                    // Обрабатываем пакет с retry логикой
                    if (batch.isNotEmpty()) {
                        processBatchWithRetry(batch)
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Batch processor отменен")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка в batch processor", e)
                    delay(1000) // Небольшая задержка перед следующей попыткой
                }
            }
            Log.d(TAG, "Batch processor завершен")
        }
    }

    /**
     * Обрабатывает пакет записей с retry логикой
     */
    private suspend fun processBatchWithRetry(batch: List<LogEntry>) {
        repeat(3) { attempt ->
            try {
                // Пакетное сохранение в БД
                saveBatchToDatabase(batch)

                // Пакетная отправка по MQTT
                sendBatchViaMqtt(batch)

                // Проверка критических событий
                batch.forEach { checkForCriticalEvents(it) }

                return // Успешно обработано
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки пакета логов (попытка ${attempt + 1})", e)
                if (attempt == 2) {
                    // Последняя попытка не удалась
                    Log.e(TAG, "Пакет из ${batch.size} записей потерян после 3 попыток")
                } else {
                    delay(1000 * (attempt + 1)) // Экспоненциальная задержка
                }
            }
        }
    }

    /**
     * Анализирует одну строку лога
     */
    private suspend fun analyzeLogLine(line: String) {
        try {
            val logEntry = parseLogLine(line)

            // Добавляем в канал для пакетной обработки
            batchChannel.trySend(logEntry)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка анализа строки лога: $line", e)
        }
    }

    /**
     * Парсит строку лога используя компилированный regex
     * Исправлено: убран memory leak через кеш результатов
     */
    private fun parseLogLine(line: String): LogEntry {
        try {
            // Используем предкомпилированный паттерн
            val match = logPattern.find(line)

            return if (match != null) {
                val timestamp = parseTimestamp(match.groupValues[1])
                val level = match.groupValues[2]
                val tag = match.groupValues[3]
                val message = match.groupValues[4]

                LogEntry(
                    timestamp = timestamp,
                    level = level,
                    tag = tag,
                    message = message,
                    rawLine = line
                )
            } else {
                // Если не удалось распарсить, создаем простую запись
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "UNKNOWN",
                    tag = "UNPARSED",
                    message = line,
                    rawLine = line
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка парсинга лога: $line", e)
            return LogEntry(
                timestamp = System.currentTimeMillis(),
                level = "ERROR",
                tag = "PARSER",
                message = "Ошибка парсинга: $line",
                rawLine = line
            )
        }
    }

    /**
     * Парсит временную метку
     */
    private fun parseTimestamp(timestampStr: String): Long {
        return try {
            timestampFormat.parse(timestampStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Пакетное сохранение логов в базу данных
     */
    private suspend fun saveBatchToDatabase(batch: List<LogEntry>) {
        try {
            val events = batch.map { logEntry ->
                val eventType = when {
                    logEntry.level == "ERROR" || logEntry.level == "FATAL" -> EventType.FANDOMAT_ERROR
                    logEntry.message.contains("crash", ignoreCase = true) -> EventType.FANDOMAT_CRASHED
                    logEntry.message.contains("restart", ignoreCase = true) -> EventType.FANDOMAT_RESTARTED
                    logEntry.message.contains("stop", ignoreCase = true) -> EventType.FANDOMAT_CRASHED
                    else -> EventType.FANDOMAT_RESTORED
                }

                val severity = when (logEntry.level) {
                    "FATAL", "ERROR" -> EventSeverity.ERROR
                    "WARN" -> EventSeverity.WARNING
                    "INFO" -> EventSeverity.INFO
                    "DEBUG" -> EventSeverity.DEBUG
                    else -> EventSeverity.INFO
                }

                Triple(eventType, "${logEntry.tag}: ${logEntry.message}", severity)
            }

            // Пакетная вставка (если DatabaseHelper поддерживает)
            events.forEach { (type, details, severity) ->
                databaseHelper.insertEvent(type, details, severity)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пакетного сохранения логов в БД", e)
        }
    }

    /**
     * Пакетная отправка логов по MQTT
     */
    private suspend fun sendBatchViaMqtt(batch: List<LogEntry>) {
        try {
            val deviceId = getDeviceId()
            val deviceName = getDeviceName()

            // Группируем по уровням для оптимизации
            val groupedLogs = batch.groupBy { it.level }

            groupedLogs.forEach { (level, logs) ->
                val batchJson = JSONObject().apply {
                    put("batch_size", logs.size)
                    put("batch_timestamp", System.currentTimeMillis())
                    put("level", level)
                    put("source", "fandomat_file_log_batch")
                    put("device_id", deviceId)
                    put("device_name", deviceName)
                    put("logs", logs.map { log ->
                        JSONObject().apply {
                            put("timestamp", log.timestamp)
                            put("tag", log.tag)
                            put("message", log.message)
                        }
                    })
                }

                val event = com.tastamat.fandomon.data.Event(
                    type = EventType.LOGS_MISSING,
                    details = batchJson.toString(),
                    severity = when (level) {
                        "ERROR", "FATAL" -> EventSeverity.ERROR
                        "WARN" -> EventSeverity.WARNING
                        else -> EventSeverity.INFO
                    }
                )

                networkSender.sendEvent(event)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка пакетной отправки логов по MQTT", e)
        }
    }

    /**
     * Проверяет критические события
     */
    private suspend fun checkForCriticalEvents(logEntry: LogEntry) {
        when {
            isCrashEvent(logEntry) -> {
                Log.w(TAG, "Обнаружен краш Fandomat: ${logEntry.message}")
                handleFandomatCrash(logEntry)
            }
            isStopEvent(logEntry) -> {
                Log.w(TAG, "Обнаружена остановка Fandomat: ${logEntry.message}")
                handleFandomatStop(logEntry)
            }
            isErrorEvent(logEntry) -> {
                Log.w(TAG, "Обнаружена критическая ошибка: ${logEntry.message}")
                handleCriticalError(logEntry)
            }
        }
    }

    /**
     * Проверяет, является ли событие крашем
     */
    private fun isCrashEvent(logEntry: LogEntry): Boolean {
        val crashKeywords = listOf(
            "crash", "fatal", "force closing", "anr",
            "segmentation fault", "signal 11", "tombstone"
        )

        return logEntry.level in listOf("FATAL", "ERROR") &&
               crashKeywords.any { keyword ->
                   logEntry.message.contains(keyword, ignoreCase = true)
               }
    }

    /**
     * Проверяет, является ли событие остановкой
     */
    private fun isStopEvent(logEntry: LogEntry): Boolean {
        val stopKeywords = listOf(
            "ondestroy", "process killed", "app stopped",
            "activity destroyed", "service stopped", "process died"
        )

        return stopKeywords.any { keyword ->
            logEntry.message.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Проверяет, является ли событие критической ошибкой
     */
    private fun isErrorEvent(logEntry: LogEntry): Boolean {
        val errorKeywords = listOf(
            "out of memory", "stackoverflow", "nullpointerexception",
            "classnotfoundexception", "illegalstateexception"
        )

        return logEntry.level == "ERROR" &&
               errorKeywords.any { keyword ->
                   logEntry.message.contains(keyword, ignoreCase = true)
               }
    }

    /**
     * Обрабатывает краш Fandomat
     */
    private suspend fun handleFandomatCrash(logEntry: LogEntry) {
        fileLogger.writeError("LogAnalyzer", "Fandomat краш обнаружен: ${logEntry.message}")

        // Записываем событие
        databaseHelper.insertEvent(
            type = EventType.FANDOMAT_CRASHED,
            details = "Краш обнаружен в логах: ${logEntry.message}",
            severity = EventSeverity.CRITICAL
        )

        // Пытаемся перезапустить
        scheduleRestart("CRASH")
    }

    /**
     * Обрабатывает остановку Fandomat
     */
    private suspend fun handleFandomatStop(logEntry: LogEntry) {
        fileLogger.writeWarning("LogAnalyzer", "Fandomat остановка обнаружена: ${logEntry.message}")

        // Ждем некоторое время и проверяем, не восстановилось ли приложение
        delay(30_000) // 30 секунд

        if (!isFandomatRunning()) {
            scheduleRestart("STOP")
        }
    }

    /**
     * Обрабатывает критическую ошибку
     */
    private suspend fun handleCriticalError(logEntry: LogEntry) {
        fileLogger.writeError("LogAnalyzer", "Критическая ошибка: ${logEntry.message}")

        databaseHelper.insertEvent(
            type = EventType.FANDOMAT_ERROR,
            details = "Критическая ошибка в логах: ${logEntry.message}",
            severity = EventSeverity.ERROR
        )
    }

    /**
     * Планирует перезапуск Fandomat
     */
    private suspend fun scheduleRestart(reason: String) {
        val currentTime = System.currentTimeMillis()

        // Проверяем cooldown
        if (currentTime - lastRestartAttempt < RESTART_COOLDOWN) {
            Log.w(TAG, "Перезапуск в cooldown режиме")
            return
        }

        // Проверяем количество попыток
        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            Log.w(TAG, "Достигнуто максимальное количество попыток перезапуска")
            fileLogger.writeError("LogAnalyzer", "Максимальное количество попыток перезапуска достигнуто")
            return
        }

        fileLogger.writeInfo("LogAnalyzer", "Планирование перезапуска Fandomat. Причина: $reason")

        // Ждем перед перезапуском
        delay(RESTART_DELAY)

        // Выполняем перезапуск
        performRestart(reason)

        lastRestartAttempt = currentTime
        restartAttempts++

        // Сбрасываем счетчик попыток через некоторое время
        scope.launch {
            delay(RESTART_COOLDOWN)
            restartAttempts = 0
        }
    }

    /**
     * Выполняет перезапуск Fandomat
     */
    private suspend fun performRestart(reason: String) {
        try {
            fileLogger.writeSystemEvent("FANDOMAT_RESTART_ATTEMPT", "Попытка перезапуска. Причина: $reason")

            // Записываем событие в БД
            databaseHelper.insertEvent(
                type = EventType.FANDOMAT_RESTARTED,
                details = "Автоматический перезапуск. Причина: $reason",
                severity = EventSeverity.WARNING
            )

            // Пытаемся запустить Fandomat
            val intent = context.packageManager.getLaunchIntentForPackage("com.tastamat.fandomat")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                fileLogger.writeSystemEvent("FANDOMAT_RESTART_SUCCESS", "Перезапуск выполнен успешно")

                // Ждем и проверяем, запустилось ли приложение
                delay(10_000)
                if (isFandomatRunning()) {
                    fileLogger.writeSystemEvent("FANDOMAT_RESTART_VERIFIED", "Перезапуск подтвержден")
                } else {
                    fileLogger.writeError("LogAnalyzer", "Перезапуск не подтвержден")
                }

            } else {
                fileLogger.writeError("LogAnalyzer", "Не удалось найти Fandomat для запуска")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перезапуска Fandomat", e)
            fileLogger.writeError("LogAnalyzer", "Ошибка перезапуска: ${e.message}", e)
        }
    }

    /**
     * Проверяет, запущен ли Fandomat с кешированием результата
     */
    private fun isFandomatRunning(): Boolean {
        val currentTime = System.currentTimeMillis()

        // Используем кешированный результат если проверка была недавно
        if (currentTime - lastProcessCheck < PROCESS_CHECK_COOLDOWN) {
            return cachedProcessState
        }

        return try {
            val process = Runtime.getRuntime().exec("pidof com.tastamat.fandomat")
            val result = process.waitFor()
            process.destroy()

            cachedProcessState = result == 0
            lastProcessCheck = currentTime

            cachedProcessState
        } catch (e: Exception) {
            cachedProcessState = false
            lastProcessCheck = currentTime
            false
        }
    }

    /**
     * Обработка неактивности файла логов
     */
    fun handleLogInactivity() {
        scope.launch {
            fileLogger.writeWarning("LogAnalyzer", "Обнаружена длительная неактивность логов Fandomat")

            if (!isFandomatRunning()) {
                fileLogger.writeWarning("LogAnalyzer", "Fandomat не запущен, планируется перезапуск")
                scheduleRestart("INACTIVITY")
            } else {
                fileLogger.writeInfo("LogAnalyzer", "Fandomat запущен, но логи неактивны")
            }
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
     * Исправлено: корректное завершение batch processor
     */
    fun cleanup() {
        isBatchProcessorActive = false
        batchChannel.close()
        scope.cancel()
        Log.d(TAG, "LogAnalyzer ресурсы освобождены")
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
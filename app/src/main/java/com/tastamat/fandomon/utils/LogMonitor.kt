package com.tastamat.fandomon.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class LogMonitor(private val context: Context) {

    companion object {
        private const val TAG = "LogMonitor"
        private const val LOGCAT_COMMAND = "logcat"
        private const val MAX_LOG_LINES = 1000
    }

    /**
     * Получает логи для указанного пакета с определенного времени
     */
    fun getLogsSince(packageName: String, sinceTimestamp: Long): List<String> {
        val logs = mutableListOf<String>()

        try {
            // Форматируем время для logcat
            val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val sinceTime = dateFormat.format(Date(sinceTimestamp))

            // Команда logcat для получения логов с определенного времени
            val command = arrayOf(
                "logcat",
                "-d", // дамп и выход
                "-t", sinceTime, // с определенного времени
                "--pid", getPidForPackage(packageName).toString() // только для нужного процесса
            )

            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            var lineCount = 0

            while (reader.readLine().also { line = it } != null && lineCount < MAX_LOG_LINES) {
                line?.let { logLine ->
                    if (logLine.contains(packageName) || isRelevantLogLine(logLine)) {
                        logs.add(logLine)
                        Log.d(TAG, "getLogsSince: " + logLine)
                        lineCount++
                    }
                }
            }

            reader.close()
            process.destroy()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения логов для $packageName", e)

            // Альтернативный метод через system logs
            return getSystemLogsAlternative(packageName, sinceTimestamp)
        }

        return logs
    }

    /**
     * Получает PID процесса для указанного пакета
     */
    private fun getPidForPackage(packageName: String): Int {
        try {
            val process = Runtime.getRuntime().exec("pidof $packageName")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val pid = reader.readLine()?.trim()?.toIntOrNull() ?: -1
            reader.close()
            process.destroy()
            return pid
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения PID для $packageName", e)
            return -1
        }
    }

    /**
     * Альтернативный метод получения логов через system properties
     */
    private fun getSystemLogsAlternative(packageName: String, sinceTimestamp: Long): List<String> {
        val logs = mutableListOf<String>()

        try {
            // Попытка чтения через буферы логов
            val radioLogBuffer = readLogBuffer("radio", packageName, sinceTimestamp)
            val mainLogBuffer = readLogBuffer("main", packageName, sinceTimestamp)
            val systemLogBuffer = readLogBuffer("system", packageName, sinceTimestamp)

            logs.addAll(radioLogBuffer)
            logs.addAll(mainLogBuffer)
            logs.addAll(systemLogBuffer)

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка альтернативного метода логов", e)
        }

        return logs.distinct() // удаляем дубликаты
    }

    /**
     * Читает определенный буфер логов
     */
    private fun readLogBuffer(buffer: String, packageName: String, sinceTimestamp: Long): List<String> {
        val logs = mutableListOf<String>()

        try {
            val command = arrayOf("logcat", "-b", buffer, "-d", "-v", "time")
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    if (isLogLineRelevant(logLine, packageName, sinceTimestamp)) {
                        logs.add(logLine)
                    }
                }
            }

            reader.close()
            process.destroy()

        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать буфер $buffer", e)
        }

        return logs
    }

    /**
     * Проверяет, является ли строка лога релевантной
     */
    private fun isLogLineRelevant(logLine: String, packageName: String, sinceTimestamp: Long): Boolean {
        // Проверяем, содержит ли строка имя пакета
        if (!logLine.contains(packageName, ignoreCase = true)) {
            return false
        }

        // Проверяем время (приблизительно)
        try {
            val timePattern = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}""")
            val timeMatch = timePattern.find(logLine)
            if (timeMatch != null) {
                val timeStr = timeMatch.value
                val logTime = parseLogTime(timeStr)
                return logTime >= sinceTimestamp
            }
        } catch (e: Exception) {
            // Если не можем парсить время, включаем лог
            return true
        }

        return true
    }

    /**
     * Парсит время из строки лога
     */
    private fun parseLogTime(timeStr: String): Long {
        return try {
            val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val calendar = Calendar.getInstance()
            val parsed = dateFormat.parse(timeStr)
            if (parsed != null) {
                // Устанавливаем текущий год
                calendar.time = parsed
                calendar.set(Calendar.YEAR, Calendar.getInstance().get(Calendar.YEAR))
                calendar.timeInMillis
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Проверяет, является ли строка лога релевантной
     */
    private fun isRelevantLogLine(logLine: String): Boolean {
        val keywords = listOf("ERROR", "FATAL", "CRASH", "ANR", "Exception", "Force closing")
        return keywords.any { keyword ->
            logLine.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Мониторинг логов в реальном времени
     */
    fun startRealtimeLogMonitoring(packageName: String, callback: (String) -> Unit) {
        Thread {
            try {
                val command = arrayOf("logcat", "-v", "time", "--pid", getPidForPackage(packageName).toString())
                val process = Runtime.getRuntime().exec(command)
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { logLine ->
                        if (logLine.contains(packageName) || isRelevantLogLine(logLine)) {
                            callback(logLine)
                        }
                    }
                }

                reader.close()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка мониторинга в реальном времени", e)
            }
        }.start()
    }

    /**
     * Получает последние N строк логов для пакета
     */
    fun getLastNLogs(packageName: String, n: Int): List<String> {
        val logs = mutableListOf<String>()

        try {
            val pid = getPidForPackage(packageName)
            if (pid == -1) {
                Log.w(TAG, "Процесс $packageName не найден")
                return logs
            }

            val command = arrayOf("logcat", "-d", "-t", n.toString(), "--pid", pid.toString())
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logs.add(it) }
            }

            reader.close()
            process.destroy()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения последних $n логов", e)
        }

        return logs
    }

    /**
     * Очищает логи (требует root)
     */
    fun clearLogs(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("logcat -c")
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки логов", e)
            false
        }
    }

    /**
     * Проверяет доступность logcat
     */
    fun isLogcatAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("logcat -d -t 1")
            val result = process.waitFor()
            process.destroy()
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "Logcat недоступен", e)
            false
        }
    }

    /**
     * Получает логи приложения Fandomat
     */
    fun getFandomatLogs(): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()

        try {
            // Ищем PID Fandomat
            val fandomatPackages = arrayOf(
                "com.tastamat.fandomat"
            )

            var foundPackage: String? = null
            var pid = -1

            for (packageName in fandomatPackages) {
                pid = getPidForPackage(packageName)
                if (pid != -1) {
                    foundPackage = packageName
                    break
                }
            }

            if (foundPackage == null || pid == -1) {
                Log.w(TAG, "Fandomat процесс не найден")
                // Возвращаем общие логи с фильтрацией по ключевым словам
                return getFilteredSystemLogs()
            } else {
                Log.d(TAG, "getFandomatLogs: Процесс найден")
            }

            // Получаем логи для найденного процесса
            val command = arrayOf("logcat", "-d", "--pid", pid.toString(), "-v", "time")
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    parseLogLine(logLine)?.let { logEntry ->
                        logs.add(logEntry)
                    }
                }
            }

            reader.close()
            process.destroy()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения логов Fandomat", e)
            // В случае ошибки возвращаем отфильтрованные системные логи
            return getFilteredSystemLogs()
        }

        return logs.sortedByDescending { it.timestamp }
    }

    /**
     * Получает отфильтрованные системные логи
     */
    private fun getFilteredSystemLogs(): List<LogEntry> {
        val logs = mutableListOf<LogEntry>()

        try {
            val command = arrayOf("logcat", "-d", "-t", "200", "-v", "time")
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    if (isFandomatRelatedLog(logLine)) {
                        parseLogLine(logLine)?.let { logEntry ->
                            logs.add(logEntry)
                        }
                    }
                }
            }

            reader.close()
            process.destroy()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения системных логов", e)
        }

        return logs.sortedByDescending { it.timestamp }
    }

    /**
     * Проверяет, связан ли лог с Fandomat
     */
    private fun isFandomatRelatedLog(logLine: String): Boolean {
        val keywords = listOf(
            "fandomat", "fandomon", "tastamat",
            "com.tastamat", "ru.tastamat"
        )

        return keywords.any { keyword ->
            logLine.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Парсит строку лога в LogEntry
     */
    private fun parseLogLine(logLine: String): LogEntry? {
        try {
            // Пример формата: 12-25 14:30:15.123  1234  5678 I TagName: Message
            val logPattern = Regex("""(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s*(.*)""")
            val match = logPattern.find(logLine)

            return if (match != null) {
                val timeStr = match.groupValues[1]
                val level = match.groupValues[4]
                val tag = match.groupValues[5].trim()
                val message = match.groupValues[6]

                val timestamp = parseLogTime(timeStr)

                LogEntry(
                    timestamp = timestamp,
                    level = level,
                    tag = tag,
                    message = message,
                    rawLine = logLine
                )
            } else {
                // Если не удалось распарсить, создаем простую запись
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = "?",
                    tag = "Unknown",
                    message = logLine,
                    rawLine = logLine
                )
            }

        } catch (e: Exception) {
            Log.w(TAG, "Ошибка парсинга лог строки: $logLine", e)
            return null
        }
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
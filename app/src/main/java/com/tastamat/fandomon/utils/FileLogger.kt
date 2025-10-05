package com.tastamat.fandomon.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class FileLogger(private val context: Context) {

    companion object {
        private const val TAG = "FileLogger"
        private const val LOG_FILE_NAME = "fandomat.logs"
        private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_BACKUP_FILES = 3
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Получает путь к файлу логов
     */
    fun getLogFilePath(): String {
        // Используем приложение-специфичное внешнее хранилище (не требует разрешений в Android 10+)
        val externalFilesDir = context.getExternalFilesDir("logs")

        // Если внешнее хранилище недоступно, используем внутреннее
        val logsDir = externalFilesDir ?: File(context.filesDir, "logs")

        if (!logsDir.exists()) {
            logsDir.mkdirs()
            Log.d(TAG, "Создана директория логов: ${logsDir.absolutePath}")
        }

        val logFile = File(logsDir, LOG_FILE_NAME)
        Log.d(TAG, "Путь к файлу логов: ${logFile.absolutePath}")

        return logFile.absolutePath
    }

    /**
     * Записывает лог в файл
     */
    fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val logFile = File(getLogFilePath())

            // Проверяем размер файла и делаем ротацию если нужно
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                rotateLogFile(logFile)
            }

            val timestamp = dateFormat.format(Date())
            val logEntry = buildString {
                append("$timestamp [$level] $tag: $message")

                if (throwable != null) {
                    append("\nException: ${throwable.javaClass.simpleName}: ${throwable.message}")
                    append("\nStackTrace: ${throwable.stackTraceToString()}")
                }

                append("\n")
            }

            // Записываем в файл
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
                writer.flush()
            }

            Log.d(TAG, "Лог записан в файл: $message")

        } catch (e: IOException) {
            Log.e(TAG, "Ошибка записи лога в файл", e)
        }
    }

    /**
     * Записывает системное событие
     */
    fun writeSystemEvent(event: String, details: String = "") {
        writeLog("SYSTEM", "FandomonMonitor", "$event: $details")
    }

    /**
     * Записывает событие приложения
     */
    fun writeAppEvent(event: String, details: String = "") {
        writeLog("APP", "FandomonApp", "$event: $details")
    }

    /**
     * Записывает ошибку
     */
    fun writeError(tag: String, message: String, throwable: Throwable? = null) {
        writeLog("ERROR", tag, message, throwable)
    }

    /**
     * Записывает предупреждение
     */
    fun writeWarning(tag: String, message: String) {
        writeLog("WARN", tag, message)
    }

    /**
     * Записывает информационное сообщение
     */
    fun writeInfo(tag: String, message: String) {
        writeLog("INFO", tag, message)
    }

    /**
     * Записывает отладочное сообщение
     */
    fun writeDebug(tag: String, message: String) {
        writeLog("DEBUG", tag, message)
    }

    /**
     * Ротация лог файла
     */
    private fun rotateLogFile(currentFile: File) {
        try {
            val baseName = currentFile.nameWithoutExtension
            val extension = currentFile.extension
            val parentDir = currentFile.parentFile

            // Сдвигаем существующие backup файлы
            for (i in MAX_BACKUP_FILES downTo 1) {
                val oldBackup = File(parentDir, "$baseName.$i.$extension")
                val newBackup = File(parentDir, "$baseName.${i + 1}.$extension")

                if (oldBackup.exists()) {
                    if (i == MAX_BACKUP_FILES) {
                        oldBackup.delete() // Удаляем самый старый
                    } else {
                        oldBackup.renameTo(newBackup)
                    }
                }
            }

            // Переименовываем текущий файл в .1
            val firstBackup = File(parentDir, "$baseName.1.$extension")
            currentFile.renameTo(firstBackup)

            Log.d(TAG, "Ротация лог файла выполнена")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка ротации лог файла", e)
        }
    }

    /**
     * Читает все логи из файла
     */
    fun readAllLogs(): List<String> {
        val logs = mutableListOf<String>()

        try {
            val logFile = File(getLogFilePath())
            if (logFile.exists()) {
                logFile.readLines().forEach { line ->
                    if (line.isNotEmpty()) {
                        logs.add(line)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка чтения лог файла", e)
        }

        return logs
    }

    /**
     * Читает последние N строк логов
     */
    fun readLastLogs(count: Int): List<String> {
        val allLogs = readAllLogs()
        return allLogs.takeLast(count)
    }

    /**
     * Читает логи начиная с определенного времени
     */
    fun readLogsSince(timestamp: Long): List<String> {
        val logs = mutableListOf<String>()
        val allLogs = readAllLogs()

        val targetDate = Date(timestamp)

        allLogs.forEach { line ->
            try {
                // Извлекаем временную метку из строки лога
                val timestampStr = line.substring(0, 23) // "yyyy-MM-dd HH:mm:ss.SSS"
                val logDate = dateFormat.parse(timestampStr)

                if (logDate != null && logDate.after(targetDate)) {
                    logs.add(line)
                }
            } catch (e: Exception) {
                // Если не можем распарсить время, включаем строку
                logs.add(line)
            }
        }

        return logs
    }

    /**
     * Очищает лог файл
     */
    fun clearLogs(): Boolean {
        return try {
            val logFile = File(getLogFilePath())
            if (logFile.exists()) {
                logFile.delete()
            }
            writeSystemEvent("LOG_CLEARED", "Файл логов очищен")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки лог файла", e)
            false
        }
    }

    /**
     * Проверяет, существует ли файл логов
     */
    fun logFileExists(): Boolean {
        return File(getLogFilePath()).exists()
    }

    /**
     * Получает размер файла логов в байтах
     */
    fun getLogFileSize(): Long {
        val logFile = File(getLogFilePath())
        return if (logFile.exists()) logFile.length() else 0
    }

    /**
     * Получает время последней модификации файла логов
     */
    fun getLastModified(): Long {
        val logFile = File(getLogFilePath())
        return if (logFile.exists()) logFile.lastModified() else 0
    }

    /**
     * Создает тестовые логи для демонстрации
     */
    fun generateTestLogs() {
        writeSystemEvent("TEST_START", "Генерация тестовых логов")
        writeAppEvent("APP_LAUNCH", "Приложение запущено")
        writeInfo("TestModule", "Тестовое информационное сообщение")
        writeWarning("TestModule", "Тестовое предупреждение")
        writeDebug("TestModule", "Тестовое отладочное сообщение")
        writeError("TestModule", "Тестовая ошибка", Exception("Тестовое исключение"))
        writeSystemEvent("TEST_END", "Генерация тестовых логов завершена")
    }
}
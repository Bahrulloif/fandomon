package com.tastamat.fandomon.utils

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class FileWatcher(
    private val context: Context,
    private val filePath: String,
    private val onFileChanged: (List<String>) -> Unit,
    private val onFileInactive: () -> Unit
) {

    companion object {
        private const val TAG = "FileWatcher"
        private const val INACTIVITY_TIMEOUT = 300_000L // 5 минут
        private const val CHECK_INTERVAL = 30_000L // 30 секунд
    }

    private var fileObserver: FileObserver? = null
    private var lastModified = 0L
    private var lastFileSize = 0L
    private var isWatching = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var inactivityJob: Job? = null
    private val newLines = ConcurrentLinkedQueue<String>()

    /**
     * Начинает мониторинг файла
     */
    fun startWatching() {
        if (isWatching) {
            Log.w(TAG, "FileWatcher уже активен")
            return
        }

        try {
            val file = File(filePath)
            val parentDir = file.parentFile

            if (parentDir == null || !parentDir.exists()) {
                Log.e(TAG, "Родительская директория не существует: ${parentDir?.absolutePath}")
                return
            }

            // Инициализируем начальное состояние
            if (file.exists()) {
                lastModified = file.lastModified()
                lastFileSize = file.length()
            }

            // Создаем FileObserver для отслеживания изменений
            fileObserver = object : FileObserver(parentDir.absolutePath, MODIFY or CREATE or DELETE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == file.name) {
                        handleFileEvent(event, file)
                    }
                }
            }

            fileObserver?.startWatching()
            isWatching = true

            // Запускаем периодическую проверку на случай если FileObserver не сработает
            startPeriodicCheck()

            // Запускаем проверку неактивности
            resetInactivityTimer()

            Log.i(TAG, "FileWatcher запущен для файла: $filePath")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска FileWatcher", e)
        }
    }

    /**
     * Останавливает мониторинг файла
     */
    fun stopWatching() {
        if (!isWatching) {
            return
        }

        try {
            fileObserver?.stopWatching()
            fileObserver = null
            isWatching = false

            scope.cancel()
            inactivityJob?.cancel()

            Log.i(TAG, "FileWatcher остановлен")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки FileWatcher", e)
        }
    }

    /**
     * Обрабатывает события файла
     */
    private fun handleFileEvent(event: Int, file: File) {
        scope.launch {
            try {
                when (event and FileObserver.ALL_EVENTS) {
                    FileObserver.MODIFY -> {
                        Log.d(TAG, "Файл изменен: ${file.name}")
                        checkForNewContent(file)
                    }
                    FileObserver.CREATE -> {
                        Log.d(TAG, "Файл создан: ${file.name}")
                        checkForNewContent(file)
                    }
                    FileObserver.DELETE_SELF -> {
                        Log.w(TAG, "Файл удален: ${file.name}")
                        // Перезапускаем мониторинг через некоторое время
                        delay(5000)
                        if (file.exists()) {
                            startWatching()
                        }
                    }
                }

                resetInactivityTimer()

            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки события файла", e)
            }
        }
    }

    /**
     * Проверяет новое содержимое файла
     */
    private suspend fun checkForNewContent(file: File) {
        if (!file.exists()) {
            return
        }

        val currentModified = file.lastModified()
        val currentSize = file.length()

        // Проверяем, изменился ли файл
        if (currentModified > lastModified || currentSize != lastFileSize) {
            Log.d(TAG, "Обнаружены изменения в файле. Размер: $lastFileSize -> $currentSize")

            val newLines = readNewLines(file, lastFileSize)

            if (newLines.isNotEmpty()) {
                Log.d(TAG, "Получено ${newLines.size} новых строк")

                // Анализируем новые строки
                analyzeLogLines(newLines)

                // Уведомляем о новых строках
                withContext(Dispatchers.Main) {
                    onFileChanged(newLines)
                }
            }

            lastModified = currentModified
            lastFileSize = currentSize
        }
    }

    /**
     * Читает новые строки из файла
     */
    private fun readNewLines(file: File, fromPosition: Long): List<String> {
        val newLines = mutableListOf<String>()

        try {
            file.useLines { lines ->
                val allLines = lines.toList()

                // Если файл стал меньше, значит он был пересоздан
                if (file.length() < fromPosition) {
                    Log.d(TAG, "Файл был пересоздан, читаем все содержимое")
                    newLines.addAll(allLines)
                } else {
                    // Читаем только новые строки
                    val existingLinesCount = (fromPosition / (allLines.joinToString("\n").length.toDouble() / allLines.size)).toInt()

                    if (existingLinesCount < allLines.size) {
                        newLines.addAll(allLines.drop(existingLinesCount))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения новых строк", e)
        }

        return newLines
    }

    /**
     * Анализирует строки логов на предмет критических событий
     */
    private fun analyzeLogLines(lines: List<String>) {
        lines.forEach { line ->
            // Проверяем на критические события
            when {
                isAppCrashLog(line) -> {
                    Log.w(TAG, "Обнаружен краш приложения: $line")
                    handleAppCrash(line)
                }
                isAppStoppedLog(line) -> {
                    Log.w(TAG, "Приложение остановлено: $line")
                    handleAppStopped(line)
                }
                isErrorLog(line) -> {
                    Log.w(TAG, "Обнаружена ошибка: $line")
                    handleError(line)
                }
            }
        }
    }

    /**
     * Проверяет, является ли лог записью о крахе приложения
     */
    private fun isAppCrashLog(line: String): Boolean {
        val crashKeywords = listOf(
            "FATAL", "CRASH", "Force closing", "ANR",
            "Exception", "Error", "java.lang.", "android.app.ActivityThread"
        )

        return crashKeywords.any { keyword ->
            line.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Проверяет, является ли лог записью об остановке приложения
     */
    private fun isAppStoppedLog(line: String): Boolean {
        val stopKeywords = listOf(
            "onDestroy", "Process killed", "App stopped",
            "Activity destroyed", "Service stopped"
        )

        return stopKeywords.any { keyword ->
            line.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * Проверяет, является ли лог записью об ошибке
     */
    private fun isErrorLog(line: String): Boolean {
        return line.contains("[ERROR]", ignoreCase = true) ||
               line.contains("ERROR", ignoreCase = true)
    }

    /**
     * Обрабатывает краш приложения
     */
    private fun handleAppCrash(line: String) {
        // Уведомляем о необходимости перезапуска
        scope.launch(Dispatchers.Main) {
            onFileInactive()
        }
    }

    /**
     * Обрабатывает остановку приложения
     */
    private fun handleAppStopped(line: String) {
        // Ждем некоторое время и проверяем, возобновилась ли активность
        scope.launch {
            delay(60_000) // Ждем 1 минуту

            val recentLines = readRecentLines(10)
            val hasRecentActivity = recentLines.any {
                !isAppStoppedLog(it) && !isAppCrashLog(it)
            }

            if (!hasRecentActivity) {
                withContext(Dispatchers.Main) {
                    onFileInactive()
                }
            }
        }
    }

    /**
     * Обрабатывает ошибку
     */
    private fun handleError(line: String) {
        Log.w(TAG, "Обработка ошибки: $line")
        // Можно добавить дополнительную логику обработки ошибок
    }

    /**
     * Читает последние N строк из файла
     */
    private fun readRecentLines(count: Int): List<String> {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.readLines().takeLast(count)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения последних строк", e)
            emptyList()
        }
    }

    /**
     * Периодическая проверка файла
     */
    private fun startPeriodicCheck() {
        scope.launch {
            while (isWatching) {
                try {
                    val file = File(filePath)
                    if (file.exists()) {
                        checkForNewContent(file)
                    }
                    delay(CHECK_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка периодической проверки", e)
                }
            }
        }
    }

    /**
     * Сбрасывает таймер неактивности
     */
    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            delay(INACTIVITY_TIMEOUT)

            Log.w(TAG, "Обнаружена длительная неактивность файла логов")
            withContext(Dispatchers.Main) {
                onFileInactive()
            }
        }
    }

    /**
     * Получает статистику мониторинга
     */
    fun getWatchingStats(): Map<String, Any> {
        val file = File(filePath)
        return mapOf(
            "isWatching" to isWatching,
            "fileExists" to file.exists(),
            "fileSize" to (if (file.exists()) file.length() else 0),
            "lastModified" to lastModified,
            "filePath" to filePath
        )
    }
}
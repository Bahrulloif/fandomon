package com.tastamat.fandomon.utils

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.*

class FandomatChecker(private val context: Context) {

    companion object {
        private const val TAG = "FandomatChecker"
        // Возможные имена пакетов Fandomat
        private val POSSIBLE_FANDOMAT_PACKAGES = arrayOf(
            "com.tastamat.fandomat",
            "com.tastamat.fandomon",
            "ru.tastamat.fandomat",
            "fandomat",
            "fandomat.app"
        )
        private const val CHECK_INTERVAL_MS = 30000L // 30 секунд
    }

    /**
     * Проверяет, запущено ли приложение Fandomat
     */
    fun isFandomatRunning(): Boolean {
        // Сначала находим правильный пакет Fandomat
        val actualPackage = findFandomatPackage()
        if (actualPackage == null) {
            Log.w(TAG, "Fandomat не найден среди установленных приложений")
            return false
        }

        // Пробуем несколько методов для надежности
        var isRunning = false

        // Метод 1: Проверяем процессы
        isRunning = isFandomatRunningActivityManager(actualPackage)
        if (isRunning) {
            Log.d(TAG, "Fandomat определен как запущенный через ActivityManager")
            return true
        }

        // Метод 2: Usage Stats (если доступно)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && hasUsageStatsPermission()) {
            isRunning = isFandomatRunningUsageStats(actualPackage)
            if (isRunning) {
                Log.d(TAG, "Fandomat определен как запущенный через UsageStats")
                return true
            }
        }

        Log.d(TAG, "Fandomat не обнаружен как запущенный")
        return false
    }

    /**
     * Находит правильное имя пакета Fandomat среди возможных вариантов
     */
    private fun findFandomatPackage(): String? {
        val packageManager = context.packageManager
        val installedPackages = packageManager.getInstalledPackages(0)

        // Ищем точное совпадение с возможными именами
        for (packageName in POSSIBLE_FANDOMAT_PACKAGES) {
            if (installedPackages.any { it.packageName == packageName }) {
                Log.d(TAG, "Найден пакет Fandomat: $packageName")
                return packageName
            }
        }

        // Ищем по частичному совпадению (содержит "fandomat")
        for (packageInfo in installedPackages) {
            if (packageInfo.packageName.contains("fandomat", ignoreCase = true)) {
                Log.d(TAG, "Найден пакет Fandomat по частичному совпадению: ${packageInfo.packageName}")
                return packageInfo.packageName
            }
        }

        // Логируем пакеты, содержащие "fandomat" или "tastamat" для отладки
        Log.w(TAG, "Поиск Fandomat среди установленных пакетов...")
        val matchingPackages = installedPackages.filter {
            it.packageName.contains("fandomat", ignoreCase = true) ||
            it.packageName.contains("tastamat", ignoreCase = true)
        }

        if (matchingPackages.isNotEmpty()) {
            Log.d(TAG, "Найдены подходящие пакеты:")
            matchingPackages.forEach { packageInfo ->
                Log.d(TAG, "- ${packageInfo.packageName}")
            }
        } else {
            Log.w(TAG, "Пакеты, содержащие 'fandomat' или 'tastamat', не найдены")
        }

        return null
    }

    /**
     * Проверка через UsageStatsManager (Android 5.0+)
     */
    private fun isFandomatRunningUsageStats(packageName: String): Boolean {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false

            val currentTime = System.currentTimeMillis()
            val startTime = currentTime - CHECK_INTERVAL_MS

            val usageStats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                currentTime
            )

            // Проверяем, был ли Fandomat активен в последние несколько секунд
            Log.d(TAG, "Анализ UsageStats для пакета: $packageName")
            Log.d(TAG, "Временной диапазон: ${Date(startTime)} - ${Date(currentTime)}")

            var fandomatFound = false
            for (stats in usageStats) {
                if (stats.packageName == packageName) {
                    fandomatFound = true
                    val lastTimeUsed = stats.lastTimeUsed
                    val timeDifference = currentTime - lastTimeUsed
                    val isRecent = timeDifference < CHECK_INTERVAL_MS * 3

                    Log.d(TAG, "Найден Fandomat ($packageName):")
                    Log.d(TAG, "  Последнее использование: ${Date(lastTimeUsed)}")
                    Log.d(TAG, "  Разница во времени: ${timeDifference}ms (${timeDifference / 1000}s)")
                    Log.d(TAG, "  Считается активным: $isRecent")

                    return isRecent
                }
            }

            if (!fandomatFound) {
                Log.w(TAG, "Fandomat ($packageName) не найден в UsageStats за указанный период")
                Log.d(TAG, "Всего записей в UsageStats: ${usageStats.size}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки через UsageStats", e)
            return isFandomatRunningActivityManager(packageName)
        }

        return false
    }

    /**
     * Проверка через ActivityManager (устаревший метод)
     */
    private fun isFandomatRunningActivityManager(packageName: String): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            Log.d(TAG, "Проверка ActivityManager для пакета: $packageName")

            // Для Android 5.0+ используем getRunningAppProcesses (ограниченная функциональность)
            val runningProcesses = activityManager.runningAppProcesses
            Log.d(TAG, "Всего запущенных процессов: ${runningProcesses?.size ?: 0}")

            runningProcesses?.forEach { processInfo ->
                Log.v(TAG, "Процесс: ${processInfo.processName}, важность: ${processInfo.importance}")
                if (processInfo.processName == packageName) {
                    Log.d(TAG, "✅ Найден процесс Fandomat: ${processInfo.processName}, PID: ${processInfo.pid}")
                    return true
                }
                // Также проверяем частичные совпадения
                if (processInfo.processName.contains(packageName)) {
                    Log.d(TAG, "✅ Найден связанный процесс Fandomat: ${processInfo.processName}, PID: ${processInfo.pid}")
                    return true
                }
            }

            // Альтернативная проверка через services
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            Log.d(TAG, "Всего запущенных сервисов: ${runningServices?.size ?: 0}")

            runningServices?.forEach { serviceInfo ->
                if (serviceInfo.service.packageName == packageName) {
                    Log.d(TAG, "✅ Найден сервис Fandomat: ${serviceInfo.service.className}")
                    return true
                }
            }

            Log.d(TAG, "❌ Fandomat не найден среди запущенных процессов и сервисов")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка проверки через ActivityManager", e)
        }

        return false
    }

    /**
     * Проверяет, установлено ли приложение Fandomat
     */
    fun isFandomatInstalled(): Boolean {
        return findFandomatPackage() != null
    }

    /**
     * Получает версию приложения Fandomat
     */
    fun getFandomatVersion(): String? {
        val packageName = findFandomatPackage() ?: return null
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Пытается запустить приложение Fandomat
     */
    fun startFandomat(): Boolean {
        val packageName = findFandomatPackage()
        if (packageName == null) {
            Log.e(TAG, "Fandomat не найден для запуска")
            return false
        }

        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                context.startActivity(launchIntent)
                Log.i(TAG, "Запущено приложение Fandomat ($packageName)")

                // Ждем немного и проверяем, запустилось ли
                Thread.sleep(3000)
                return isFandomatRunning()

            } else {
                Log.e(TAG, "Не найден launcher intent для Fandomat ($packageName)")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска Fandomat", e)
            false
        }
    }

    /**
     * Пытается перезапустить приложение Fandomat
     */
    fun restartFandomat(): Boolean {
        return try {
            // Сначала пытаемся "убить" процесс (требует разрешений)
            killFandomat()

            // Ждем немного
            Thread.sleep(2000)

            // Запускаем заново
            startFandomat()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка перезапуска Fandomat", e)
            false
        }
    }

    /**
     * Пытается завершить процесс Fandomat (ограниченные возможности без root)
     */
    private fun killFandomat() {
        val packageName = findFandomatPackage() ?: return

        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // Используем killBackgroundProcesses (ограниченная функциональность)
            activityManager.killBackgroundProcesses(packageName)

            Log.d(TAG, "Попытка завершения фоновых процессов Fandomat ($packageName)")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка завершения процесса Fandomat", e)
        }
    }

    /**
     * Получает информацию о процессе Fandomat
     */
    fun getFandomatProcessInfo(): ProcessInfo? {
        val packageName = findFandomatPackage() ?: return null

        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = activityManager.runningAppProcesses

            runningProcesses?.forEach { processInfo ->
                if (processInfo.processName == packageName) {
                    return ProcessInfo(
                        pid = processInfo.pid,
                        uid = processInfo.uid,
                        processName = processInfo.processName,
                        importance = processInfo.importance,
                        importanceReasonCode = processInfo.importanceReasonCode
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка получения информации о процессе", e)
        }

        return null
    }

    /**
     * Проверяет, нужны ли разрешения для Usage Stats
     */
    fun hasUsageStatsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager != null) {
                val currentTime = System.currentTimeMillis()
                val usageStats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    currentTime - 1000 * 60,
                    currentTime
                )
                usageStats.isNotEmpty()
            } else {
                false
            }
        } else {
            true // На старых версиях Android не требуется
        }
    }

    /**
     * Открывает настройки Usage Stats для получения разрешения
     */
    fun openUsageStatsSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия настроек Usage Stats", e)
        }
    }

    /**
     * Данные о процессе
     */
    data class ProcessInfo(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val importance: Int,
        val importanceReasonCode: Int
    ) {
        val importanceName: String
            get() = when (importance) {
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "BACKGROUND"
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "EMPTY"
                else -> "UNKNOWN($importance)"
            }

        override fun toString(): String {
            return "ProcessInfo(pid=$pid, uid=$uid, name=$processName, importance=$importanceName)"
        }
    }
}
package com.tastamat.fandomon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Получено событие загрузки: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_REBOOT -> {
                handleBootCompleted(context, intent.action ?: "unknown")
            }
        }
    }

    private fun handleBootCompleted(context: Context, action: String) {
        Log.i(TAG, "Система загружена, запускаем сервис мониторинга")

        // Записываем событие в базу
        val databaseHelper = DatabaseHelper(context)
        databaseHelper.insertEvent(
            EventType.SYSTEM_REBOOT,
            "Система перезагружена (событие: $action)",
            EventSeverity.INFO,
            createBootDetailsJson()
        )

        // Запускаем сервис мониторинга
        startMonitoringService(context)
    }

    private fun startMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, FandomonMonitoringService::class.java)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Log.i(TAG, "Сервис мониторинга запущен после загрузки")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сервиса мониторинга", e)

            // Записываем ошибку в базу
            val databaseHelper = DatabaseHelper(context)
            databaseHelper.insertEvent(
                EventType.FANDOMON_ERROR,
                "Ошибка запуска сервиса после загрузки: ${e.message}",
                EventSeverity.ERROR
            )
        }
    }

    private fun createBootDetailsJson(): String {
        return """
            {
                "boot_time": ${System.currentTimeMillis()},
                "device_model": "${android.os.Build.MODEL}",
                "android_version": "${android.os.Build.VERSION.RELEASE}",
                "sdk_int": ${android.os.Build.VERSION.SDK_INT},
                "manufacturer": "${android.os.Build.MANUFACTURER}",
                "brand": "${android.os.Build.BRAND}",
                "uptime": ${android.os.SystemClock.uptimeMillis()}
            }
        """.trimIndent()
    }
}
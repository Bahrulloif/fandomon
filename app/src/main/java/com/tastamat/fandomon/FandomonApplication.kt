package com.tastamat.fandomon

import android.app.Application
import android.content.Intent
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType

class FandomonApplication : Application() {

    companion object {
        private const val TAG = "FandomonApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Fandomon Application создано")

        // Инициализация базы данных
        initializeDatabase()

        // Запуск сервиса мониторинга
        startMonitoringService()
    }

    private fun initializeDatabase() {
        try {
            val databaseHelper = DatabaseHelper(this)
            databaseHelper.insertEvent(
                EventType.FANDOMON_STARTED,
                "Приложение Fandomon запущено",
                EventSeverity.INFO
            )
            Log.d(TAG, "База данных инициализирована")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации базы данных", e)
        }
    }

    private fun startMonitoringService() {
        try {
            val serviceIntent = Intent(this, FandomonMonitoringService::class.java)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Log.d(TAG, "Сервис мониторинга запущен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сервиса мониторинга", e)
        }
    }
}
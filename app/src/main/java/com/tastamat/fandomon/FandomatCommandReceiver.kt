package com.tastamat.fandomon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType
import com.tastamat.fandomon.utils.FandomatChecker

class FandomatCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FandomatCommandReceiver"
        const val ACTION_RESTART_FANDOMAT = "com.tastamat.fandomon.RESTART_FANDOMAT"
        const val ACTION_CHECK_FANDOMAT = "com.tastamat.fandomon.CHECK_FANDOMAT"

        fun sendRestartCommand(context: Context) {
            val intent = Intent(ACTION_RESTART_FANDOMAT)
            context.sendBroadcast(intent)
        }

        fun sendCheckCommand(context: Context) {
            val intent = Intent(ACTION_CHECK_FANDOMAT)
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Получена команда: ${intent.action}")

        when (intent.action) {
            ACTION_RESTART_FANDOMAT -> {
                handleRestartFandomat(context)
            }
            ACTION_CHECK_FANDOMAT -> {
                handleCheckFandomat(context)
            }
        }
    }

    private fun handleRestartFandomat(context: Context) {
        Log.i(TAG, "Выполняется команда перезапуска Fandomat")

        val databaseHelper = DatabaseHelper(context)
        val fandomatChecker = FandomatChecker(context)

        try {
            // Записываем команду в базу
            databaseHelper.insertEvent(
                EventType.REMOTE_COMMAND_RECEIVED,
                "Получена команда перезапуска Fandomat",
                EventSeverity.INFO
            )

            // Выполняем перезапуск
            val success = fandomatChecker.restartFandomat()

            if (success) {
                databaseHelper.insertEvent(
                    EventType.REMOTE_COMMAND_EXECUTED,
                    "Команда перезапуска Fandomat выполнена успешно",
                    EventSeverity.INFO
                )
            } else {
                databaseHelper.insertEvent(
                    EventType.FANDOMAT_START_FAILED,
                    "Не удалось выполнить команду перезапуска Fandomat",
                    EventSeverity.ERROR
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выполнения команды перезапуска", e)
            databaseHelper.insertEvent(
                EventType.FANDOMON_ERROR,
                "Ошибка выполнения команды перезапуска: ${e.message}",
                EventSeverity.ERROR
            )
        }
    }

    private fun handleCheckFandomat(context: Context) {
        Log.i(TAG, "Выполняется проверка статуса Fandomat")

        val databaseHelper = DatabaseHelper(context)
        val fandomatChecker = FandomatChecker(context)

        try {
            // Записываем команду в базу
            databaseHelper.insertEvent(
                EventType.REMOTE_COMMAND_RECEIVED,
                "Получена команда проверки статуса Fandomat",
                EventSeverity.INFO
            )

            // Проверяем статус
            val isRunning = fandomatChecker.isFandomatRunning()
            val processInfo = fandomatChecker.getFandomatProcessInfo()
            val version = fandomatChecker.getFandomatVersion()

            val statusDetails = """
                Статус: ${if (isRunning) "Запущен" else "Не запущен"}
                Версия: ${version ?: "Неизвестно"}
                Процесс: ${processInfo?.toString() ?: "Не найден"}
            """.trimIndent()

            databaseHelper.insertEvent(
                EventType.REMOTE_COMMAND_EXECUTED,
                "Проверка статуса Fandomat выполнена",
                EventSeverity.INFO,
                statusDetails
            )

            // Если не запущен, пытаемся запустить
            if (!isRunning) {
                Log.w(TAG, "Fandomat не запущен, пытаемся запустить")
                val startSuccess = fandomatChecker.startFandomat()

                if (startSuccess) {
                    databaseHelper.insertEvent(
                        EventType.FANDOMAT_RESTARTED,
                        "Fandomat автоматически запущен после проверки",
                        EventSeverity.INFO
                    )
                } else {
                    databaseHelper.insertEvent(
                        EventType.FANDOMAT_START_FAILED,
                        "Не удалось запустить Fandomat после проверки",
                        EventSeverity.ERROR
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка выполнения команды проверки", e)
            databaseHelper.insertEvent(
                EventType.FANDOMON_ERROR,
                "Ошибка выполнения команды проверки: ${e.message}",
                EventSeverity.ERROR
            )
        }
    }

}
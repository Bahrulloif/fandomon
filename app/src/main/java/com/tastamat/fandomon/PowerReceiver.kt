package com.tastamat.fandomon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType

class PowerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Получено событие питания: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                handlePowerConnected(context)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePowerDisconnected(context)
            }
            Intent.ACTION_BATTERY_LOW -> {
                handleBatteryLow(context)
            }
            Intent.ACTION_BATTERY_OKAY -> {
                handleBatteryOkay(context)
            }
        }
    }

    private fun handlePowerConnected(context: Context) {
        Log.i(TAG, "Питание подключено")

        val batteryInfo = getBatteryInfo(context)
        val databaseHelper = DatabaseHelper(context)

        databaseHelper.insertEvent(
            EventType.POWER_CONNECTED,
            "Питание подключено. Уровень батареи: ${batteryInfo.level}%",
            EventSeverity.INFO,
            createBatteryDetailsJson(batteryInfo)
        )

        // Обновляем статус питания в базе
        databaseHelper.updatePowerStatus(
            isCharging = true,
            batteryLevel = batteryInfo.level,
            temperature = batteryInfo.temperature
        )
    }

    private fun handlePowerDisconnected(context: Context) {
        Log.w(TAG, "Питание отключено")

        val batteryInfo = getBatteryInfo(context)
        val databaseHelper = DatabaseHelper(context)

        databaseHelper.insertEvent(
            EventType.POWER_DISCONNECTED,
            "Питание отключено. Уровень батареи: ${batteryInfo.level}%",
            EventSeverity.WARNING,
            createBatteryDetailsJson(batteryInfo)
        )

        // Обновляем статус питания в базе
        databaseHelper.updatePowerStatus(
            isCharging = false,
            batteryLevel = batteryInfo.level,
            temperature = batteryInfo.temperature
        )

        // Проверяем критический уровень батареи
        if (batteryInfo.level <= 10) {
            databaseHelper.insertEvent(
                EventType.BATTERY_CRITICAL,
                "Критически низкий заряд батареи при отключении питания: ${batteryInfo.level}%",
                EventSeverity.CRITICAL
            )
        }
    }

    private fun handleBatteryLow(context: Context) {
        Log.w(TAG, "Низкий заряд батареи")

        val batteryInfo = getBatteryInfo(context)
        val databaseHelper = DatabaseHelper(context)

        databaseHelper.insertEvent(
            EventType.POWER_LOW,
            "Низкий заряд батареи: ${batteryInfo.level}%",
            EventSeverity.WARNING,
            createBatteryDetailsJson(batteryInfo)
        )
    }

    private fun handleBatteryOkay(context: Context) {
        Log.i(TAG, "Уровень батареи в норме")

        val batteryInfo = getBatteryInfo(context)
        val databaseHelper = DatabaseHelper(context)

        databaseHelper.insertEvent(
            EventType.POWER_CONNECTED,
            "Уровень батареи восстановлен: ${batteryInfo.level}%",
            EventSeverity.INFO,
            createBatteryDetailsJson(batteryInfo)
        )
    }

    private fun getBatteryInfo(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        val temperature = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 10.0f

        // Получаем дополнительную информацию через Intent
        val batteryStatus = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val technology = batteryStatus?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"

        return BatteryInfo(
            level = level,
            isCharging = isCharging,
            temperature = temperature,
            status = status,
            plugged = plugged,
            voltage = voltage,
            technology = technology
        )
    }

    private fun createBatteryDetailsJson(batteryInfo: BatteryInfo): String {
        return """
            {
                "level": ${batteryInfo.level},
                "is_charging": ${batteryInfo.isCharging},
                "temperature": ${batteryInfo.temperature},
                "status": "${getStatusString(batteryInfo.status)}",
                "plugged": "${getPluggedString(batteryInfo.plugged)}",
                "voltage": ${batteryInfo.voltage},
                "technology": "${batteryInfo.technology}",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()
    }

    private fun getStatusString(status: Int): String {
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
            else -> "Unknown ($status)"
        }
    }

    private fun getPluggedString(plugged: Int): String {
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            0 -> "Not Plugged"
            else -> "Unknown ($plugged)"
        }
    }

    data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean,
        val temperature: Float,
        val status: Int,
        val plugged: Int,
        val voltage: Int,
        val technology: String
    )
}
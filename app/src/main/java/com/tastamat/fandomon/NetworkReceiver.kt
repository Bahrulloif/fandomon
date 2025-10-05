package com.tastamat.fandomon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.tastamat.fandomon.data.DatabaseHelper
import com.tastamat.fandomon.data.EventSeverity
import com.tastamat.fandomon.data.EventType

class NetworkReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Получено сетевое событие: ${intent.action}")

        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                handleConnectivityChange(context)
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                handleWifiStateChange(context, intent)
            }
        }
    }

    private fun handleConnectivityChange(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

        val isConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        val databaseHelper = DatabaseHelper(context)

        if (isConnected && isValidated) {
            Log.i(TAG, "Интернет соединение восстановлено")
            databaseHelper.insertEvent(
                EventType.NETWORK_RESTORED,
                "Интернет соединение восстановлено",
                EventSeverity.INFO,
                getNetworkDetails(networkCapabilities)
            )
        } else {
            Log.w(TAG, "Потеря интернет соединения")
            databaseHelper.insertEvent(
                EventType.NETWORK_DISCONNECTED,
                "Потеря интернет соединения",
                EventSeverity.WARNING,
                getNetworkDetails(networkCapabilities)
            )
        }

        // Проверяем скорость соединения
        checkNetworkSpeed(context, networkCapabilities)
    }

    private fun handleWifiStateChange(context: Context, intent: Intent) {
        val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
        val databaseHelper = DatabaseHelper(context)

        when (wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> {
                Log.i(TAG, "WiFi включен")
                databaseHelper.insertEvent(
                    EventType.NETWORK_RESTORED,
                    "WiFi включен",
                    EventSeverity.INFO
                )
            }
            WifiManager.WIFI_STATE_DISABLED -> {
                Log.w(TAG, "WiFi отключен")
                databaseHelper.insertEvent(
                    EventType.NETWORK_DISCONNECTED,
                    "WiFi отключен",
                    EventSeverity.WARNING
                )
            }
            WifiManager.WIFI_STATE_DISABLING -> {
                Log.w(TAG, "WiFi отключается")
            }
            WifiManager.WIFI_STATE_ENABLING -> {
                Log.i(TAG, "WiFi включается")
            }
        }
    }

    private fun getNetworkDetails(networkCapabilities: NetworkCapabilities?): String {
        if (networkCapabilities == null) return "Нет активной сети"

        val details = mutableListOf<String>()

        // Тип соединения
        when {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                details.add("Тип: WiFi")
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                details.add("Тип: Мобильная сеть")
            }
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                details.add("Тип: Ethernet")
            }
            else -> {
                details.add("Тип: Неизвестно")
            }
        }

        // Возможности сети
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            details.add("Интернет: Доступен")
        } else {
            details.add("Интернет: Недоступен")
        }

        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            details.add("Проверено: Да")
        } else {
            details.add("Проверено: Нет")
        }

        // Скорость (если доступно)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val downstreamBandwidth = networkCapabilities.linkDownstreamBandwidthKbps
            val upstreamBandwidth = networkCapabilities.linkUpstreamBandwidthKbps

            if (downstreamBandwidth > 0) {
                details.add("Скорость загрузки: ${downstreamBandwidth} Kbps")
            }
            if (upstreamBandwidth > 0) {
                details.add("Скорость отправки: ${upstreamBandwidth} Kbps")
            }
        }

        return details.joinToString(", ")
    }

    private fun checkNetworkSpeed(context: Context, networkCapabilities: NetworkCapabilities?) {
        if (networkCapabilities == null) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val downstreamBandwidth = networkCapabilities.linkDownstreamBandwidthKbps

            // Считаем соединение медленным, если скорость меньше 1 Mbps
            if (downstreamBandwidth in 1..1000) {
                val databaseHelper = DatabaseHelper(context)
                databaseHelper.insertEvent(
                    EventType.NETWORK_SLOW,
                    "Медленное интернет соединение: $downstreamBandwidth Kbps",
                    EventSeverity.WARNING
                )
            }
        }
    }
}
package com.tastamat.fandomon.data

enum class EventType(val value: String, val description: String) {
    // События приложения Fandomat
    FANDOMAT_CRASHED("fandomat_crashed", "Падение приложения Fandomat"),
    FANDOMAT_RESTARTED("fandomat_restarted", "Перезапуск приложения Fandomat"),
    FANDOMAT_START_FAILED("fandomat_start_failed", "Неудачный запуск Fandomat"),
    FANDOMAT_RESTORED("fandomat_restored", "Приложение Fandomat восстановлено"),
    FANDOMAT_ERROR("fandomat_error", "Ошибка в приложении Fandomat"),

    // События сети
    NETWORK_DISCONNECTED("network_disconnected", "Отключение от сети"),
    NETWORK_RESTORED("network_restored", "Восстановление сети"),
    NETWORK_SLOW("network_slow", "Медленная сеть"),

    // События питания
    POWER_DISCONNECTED("power_disconnected", "Отключение питания"),
    POWER_CONNECTED("power_connected", "Подключение питания"),
    POWER_LOW("power_low", "Низкий заряд батареи"),
    BATTERY_CRITICAL("battery_critical", "Критически низкий заряд"),

    // События логов
    LOGS_MISSING("logs_missing", "Отсутствие логов"),
    LOGS_ERROR("logs_error", "Ошибки в логах"),

    // События Fandomon
    FANDOMON_STARTED("fandomon_started", "Запуск Fandomon"),
    FANDOMON_STOPPED("fandomon_stopped", "Остановка Fandomon"),
    FANDOMON_ERROR("fandomon_error", "Ошибка Fandomon"),

    // Системные события
    SYSTEM_REBOOT("system_reboot", "Перезагрузка системы"),
    SYSTEM_SHUTDOWN("system_shutdown", "Выключение системы"),
    DEVICE_ADMIN_ACTIVATED("device_admin_activated", "Активация администратора устройства"),

    // События удаленного управления
    REMOTE_COMMAND_RECEIVED("remote_command_received", "Получена удаленная команда"),
    REMOTE_COMMAND_EXECUTED("remote_command_executed", "Выполнена удаленная команда"),
    REMOTE_CONNECTION_FAILED("remote_connection_failed", "Ошибка удаленного подключения");

    companion object {
        fun fromString(value: String): EventType? {
            return values().find { it.value == value }
        }
    }
}
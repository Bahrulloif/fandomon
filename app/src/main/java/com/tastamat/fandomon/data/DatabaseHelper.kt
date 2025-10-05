package com.tastamat.fandomon.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DATABASE_NAME = "fandomon.db"
        private const val DATABASE_VERSION = 1

        // Таблица событий
        private const val TABLE_EVENTS = "events"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_DETAILS = "details"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_IS_SENT = "is_sent"
        private const val COLUMN_SENT_TIMESTAMP = "sent_timestamp"
        private const val COLUMN_SEVERITY = "severity"
        private const val COLUMN_ADDITIONAL_DATA = "additional_data"

        // Таблица статуса питания
        private const val TABLE_POWER_STATUS = "power_status"
        private const val COLUMN_PS_ID = "id"
        private const val COLUMN_PS_TIMESTAMP = "timestamp"
        private const val COLUMN_PS_IS_CHARGING = "is_charging"
        private const val COLUMN_PS_BATTERY_LEVEL = "battery_level"
        private const val COLUMN_PS_TEMPERATURE = "temperature"

        // Таблица настроек
        private const val TABLE_SETTINGS = "settings"
        private const val COLUMN_SETTING_KEY = "key"
        private const val COLUMN_SETTING_VALUE = "value"
        private const val COLUMN_SETTING_UPDATED = "updated_timestamp"

        // SQL для создания таблиц
        private const val CREATE_TABLE_EVENTS = """
            CREATE TABLE $TABLE_EVENTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TYPE TEXT NOT NULL,
                $COLUMN_DETAILS TEXT NOT NULL,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_IS_SENT INTEGER DEFAULT 0,
                $COLUMN_SENT_TIMESTAMP INTEGER,
                $COLUMN_SEVERITY TEXT DEFAULT 'info',
                $COLUMN_ADDITIONAL_DATA TEXT
            )
        """

        private const val CREATE_TABLE_POWER_STATUS = """
            CREATE TABLE $TABLE_POWER_STATUS (
                $COLUMN_PS_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_PS_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PS_IS_CHARGING INTEGER NOT NULL,
                $COLUMN_PS_BATTERY_LEVEL INTEGER NOT NULL,
                $COLUMN_PS_TEMPERATURE REAL
            )
        """

        private const val CREATE_TABLE_SETTINGS = """
            CREATE TABLE $TABLE_SETTINGS (
                $COLUMN_SETTING_KEY TEXT PRIMARY KEY,
                $COLUMN_SETTING_VALUE TEXT,
                $COLUMN_SETTING_UPDATED INTEGER NOT NULL
            )
        """

        // Индексы для оптимизации
        private const val CREATE_INDEX_EVENTS_TIMESTAMP =
            "CREATE INDEX idx_events_timestamp ON $TABLE_EVENTS($COLUMN_TIMESTAMP)"
        private const val CREATE_INDEX_EVENTS_TYPE =
            "CREATE INDEX idx_events_type ON $TABLE_EVENTS($COLUMN_TYPE)"
        private const val CREATE_INDEX_EVENTS_SENT =
            "CREATE INDEX idx_events_sent ON $TABLE_EVENTS($COLUMN_IS_SENT)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.d(TAG, "Создание базы данных")

        db.execSQL(CREATE_TABLE_EVENTS)
        db.execSQL(CREATE_TABLE_POWER_STATUS)
        db.execSQL(CREATE_TABLE_SETTINGS)

        // Создание индексов
        db.execSQL(CREATE_INDEX_EVENTS_TIMESTAMP)
        db.execSQL(CREATE_INDEX_EVENTS_TYPE)
        db.execSQL(CREATE_INDEX_EVENTS_SENT)

        // Вставляем начальные настройки
        insertInitialSettings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.d(TAG, "Обновление базы данных с версии $oldVersion до $newVersion")

        // В будущем здесь будут миграции
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_POWER_STATUS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SETTINGS")
        onCreate(db)
    }

    private fun insertInitialSettings(db: SQLiteDatabase) {
        val settings = mapOf(
            "monitoring_interval" to "30",
            "status_send_interval" to "300",
            "auto_restart_fandomat" to "true",
            "log_retention_days" to "7",
            "max_events_per_batch" to "100",
            "server_url" to "",
            "mqtt_broker" to "",
            "device_name" to android.os.Build.MODEL
        )

        settings.forEach { (key, value) ->
            val contentValues = ContentValues().apply {
                put(COLUMN_SETTING_KEY, key)
                put(COLUMN_SETTING_VALUE, value)
                put(COLUMN_SETTING_UPDATED, System.currentTimeMillis())
            }
            db.insert(TABLE_SETTINGS, null, contentValues)
        }
    }

    /**
     * Вставляет новое событие в базу данных
     */
    fun insertEvent(type: EventType, details: String, severity: EventSeverity = EventSeverity.INFO, additionalData: String? = null): Long {
        return try {
            val db = writableDatabase
            val contentValues = ContentValues().apply {
                put(COLUMN_TYPE, type.value)
                put(COLUMN_DETAILS, details)
                put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_IS_SENT, 0)
                put(COLUMN_SEVERITY, severity.value)
                put(COLUMN_ADDITIONAL_DATA, additionalData)
            }

            val id = db.insert(TABLE_EVENTS, null, contentValues)
            Log.d(TAG, "Добавлено событие: $type - $details (ID: $id)")

            // Очищаем старые события
            cleanupOldEvents()

            id
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка вставки события: ${e.message}")
            -1L
        }
    }

    /**
     * Получает все неотправленные события
     */
    fun getPendingEvents(): List<Event> {
        val events = mutableListOf<Event>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS,
            null,
            "$COLUMN_IS_SENT = ?",
            arrayOf("0"),
            null,
            null,
            "$COLUMN_TIMESTAMP ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                events.add(Event.fromCursor(it))
            }
        }

        return events
    }

    /**
     * Помечает событие как отправленное
     */
    fun markEventAsSent(eventId: Long): Boolean {
        val db = writableDatabase
        val contentValues = ContentValues().apply {
            put(COLUMN_IS_SENT, 1)
            put(COLUMN_SENT_TIMESTAMP, System.currentTimeMillis())
        }

        val rowsUpdated = db.update(
            TABLE_EVENTS,
            contentValues,
            "$COLUMN_ID = ?",
            arrayOf(eventId.toString())
        )

        return rowsUpdated > 0
    }

    /**
     * Получает события за определенный период
     */
    fun getEventsBetween(startTime: Long, endTime: Long): List<Event> {
        val events = mutableListOf<Event>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS,
            null,
            "$COLUMN_TIMESTAMP BETWEEN ? AND ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null,
            null,
            "$COLUMN_TIMESTAMP DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                events.add(Event.fromCursor(it))
            }
        }

        return events
    }

    /**
     * Получает события определенного типа
     */
    fun getEventsByType(type: EventType, limit: Int = 100): List<Event> {
        val events = mutableListOf<Event>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EVENTS,
            null,
            "$COLUMN_TYPE = ?",
            arrayOf(type.value),
            null,
            null,
            "$COLUMN_TIMESTAMP DESC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                events.add(Event.fromCursor(it))
            }
        }

        return events
    }

    /**
     * Обновляет статус питания
     */
    fun updatePowerStatus(isCharging: Boolean, batteryLevel: Int, temperature: Float? = null) {
        val db = writableDatabase
        val contentValues = ContentValues().apply {
            put(COLUMN_PS_TIMESTAMP, System.currentTimeMillis())
            put(COLUMN_PS_IS_CHARGING, if (isCharging) 1 else 0)
            put(COLUMN_PS_BATTERY_LEVEL, batteryLevel)
            temperature?.let { put(COLUMN_PS_TEMPERATURE, it) }
        }

        db.insert(TABLE_POWER_STATUS, null, contentValues)

        // Удаляем старые записи (оставляем только за последние 24 часа)
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        db.delete(TABLE_POWER_STATUS, "$COLUMN_PS_TIMESTAMP < ?", arrayOf(dayAgo.toString()))
    }

    /**
     * Получает последний статус питания
     */
    fun getLastPowerStatus(): PowerStatus? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_POWER_STATUS,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_PS_TIMESTAMP DESC",
            "1"
        )

        cursor.use {
            if (it.moveToFirst()) {
                return PowerStatus(
                    timestamp = it.getLong(it.getColumnIndexOrThrow(COLUMN_PS_TIMESTAMP)),
                    isCharging = it.getInt(it.getColumnIndexOrThrow(COLUMN_PS_IS_CHARGING)) == 1,
                    batteryLevel = it.getInt(it.getColumnIndexOrThrow(COLUMN_PS_BATTERY_LEVEL)),
                    temperature = it.getFloat(it.getColumnIndexOrThrow(COLUMN_PS_TEMPERATURE))
                )
            }
        }

        return null
    }

    /**
     * Сохраняет настройку
     */
    fun saveSetting(key: String, value: String) {
        val db = writableDatabase
        val contentValues = ContentValues().apply {
            put(COLUMN_SETTING_KEY, key)
            put(COLUMN_SETTING_VALUE, value)
            put(COLUMN_SETTING_UPDATED, System.currentTimeMillis())
        }

        db.insertWithOnConflict(TABLE_SETTINGS, null, contentValues, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Получает настройку
     */
    fun getSetting(key: String, defaultValue: String = ""): String {
        return try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE_SETTINGS,
                arrayOf(COLUMN_SETTING_VALUE),
                "$COLUMN_SETTING_KEY = ?",
                arrayOf(key),
                null,
                null,
                null
            )

            cursor.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndexOrThrow(COLUMN_SETTING_VALUE))
                }
            }

            defaultValue
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка получения настройки $key: ${e.message}")
            defaultValue
        }
    }

    /**
     * Получает все настройки
     */
    fun getAllSettings(): Map<String, String> {
        val settings = mutableMapOf<String, String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_SETTINGS, null, null, null, null, null, null)

        cursor.use {
            while (it.moveToNext()) {
                val key = it.getString(it.getColumnIndexOrThrow(COLUMN_SETTING_KEY))
                val value = it.getString(it.getColumnIndexOrThrow(COLUMN_SETTING_VALUE))
                settings[key] = value
            }
        }

        return settings
    }

    /**
     * Очищает старые события
     */
    private fun cleanupOldEvents() {
        val retentionDays = getSetting("log_retention_days", "7").toIntOrNull() ?: 7
        val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)

        val db = writableDatabase
        val deletedRows = db.delete(TABLE_EVENTS, "$COLUMN_TIMESTAMP < ?", arrayOf(cutoffTime.toString()))

        if (deletedRows > 0) {
            Log.d(TAG, "Удалено $deletedRows старых событий")
        }
    }

    /**
     * Получает статистику событий
     */
    fun getEventStatistics(): EventStatistics {
        val db = readableDatabase

        // Общее количество событий
        val totalCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)
        val totalEvents = totalCursor.use {
            it.moveToFirst()
            it.getInt(0)
        }

        // Неотправленные события
        val pendingCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS WHERE $COLUMN_IS_SENT = 0", null)
        val pendingEvents = pendingCursor.use {
            it.moveToFirst()
            it.getInt(0)
        }

        // События за последние 24 часа
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val recentCursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS WHERE $COLUMN_TIMESTAMP > ?", arrayOf(dayAgo.toString()))
        val recentEvents = recentCursor.use {
            it.moveToFirst()
            it.getInt(0)
        }

        return EventStatistics(totalEvents, pendingEvents, recentEvents)
    }

    /**
     * Очищает все данные (для сброса)
     */
    fun clearAllData() {
        val db = writableDatabase
        db.delete(TABLE_EVENTS, null, null)
        db.delete(TABLE_POWER_STATUS, null, null)
        Log.d(TAG, "Все данные очищены")
    }

    data class PowerStatus(
        val timestamp: Long,
        val isCharging: Boolean,
        val batteryLevel: Int,
        val temperature: Float
    )

    data class EventStatistics(
        val totalEvents: Int,
        val pendingEvents: Int,
        val recentEvents: Int
    )
}
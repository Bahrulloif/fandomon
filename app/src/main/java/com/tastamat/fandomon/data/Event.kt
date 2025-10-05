package com.tastamat.fandomon.data

data class Event(
    val id: Long = 0,
    val type: EventType,
    val details: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean = false,
    val sentTimestamp: Long? = null,
    val severity: EventSeverity = EventSeverity.INFO,
    val additionalData: String? = null
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "type" to type.value,
            "details" to details,
            "timestamp" to timestamp,
            "is_sent" to isSent,
            "sent_timestamp" to sentTimestamp,
            "severity" to severity.value,
            "additional_data" to additionalData
        )
    }

    companion object {
        fun fromCursor(cursor: android.database.Cursor): Event {
            return Event(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                type = EventType.fromString(cursor.getString(cursor.getColumnIndexOrThrow("type"))) ?: EventType.FANDOMON_ERROR,
                details = cursor.getString(cursor.getColumnIndexOrThrow("details")),
                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                isSent = cursor.getInt(cursor.getColumnIndexOrThrow("is_sent")) == 1,
                sentTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow("sent_timestamp")).takeIf { it > 0 },
                severity = EventSeverity.fromValue(cursor.getString(cursor.getColumnIndexOrThrow("severity"))) ?: EventSeverity.INFO,
                additionalData = cursor.getString(cursor.getColumnIndexOrThrow("additional_data"))
            )
        }
    }
}

enum class EventSeverity(val value: String, val level: Int) {
    DEBUG("debug", 0),
    INFO("info", 1),
    WARNING("warning", 2),
    ERROR("error", 3),
    CRITICAL("critical", 4);

    companion object {
        fun fromValue(value: String?): EventSeverity? {
            return values().find { it.value == value }
        }
    }
}
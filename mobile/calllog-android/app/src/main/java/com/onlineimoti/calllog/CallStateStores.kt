package com.onlineimoti.calllog

import android.content.Context

data class ActiveCallRecord(
    val number: String,
    val direction: String,
    val startedAt: Long,
)

object CallLifecycleStore {
    private const val PREFS = "callreport_call_lifecycle"
    private const val KEY_ACTIVE = "active"
    private const val KEY_NUMBER = "number"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_STARTED_AT = "started_at"
    private const val ACTIVE_TTL_MS = 6 * 60 * 60 * 1000L

    fun markActive(context: Context, number: String, direction: String) {
        if (number.isBlank() || direction.isBlank()) {
            return
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_NUMBER, number)
            .putString(KEY_DIRECTION, direction)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun takeEndedCall(context: Context): ActiveCallRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val number = prefs.getString(KEY_NUMBER, "").orEmpty()
        val direction = prefs.getString(KEY_DIRECTION, "").orEmpty()
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)

        prefs.edit().clear().apply()

        if (!active || number.isBlank() || direction.isBlank()) {
            return null
        }
        if (System.currentTimeMillis() - startedAt > ACTIVE_TTL_MS) {
            return null
        }

        return ActiveCallRecord(number = number, direction = direction, startedAt = startedAt)
    }
}

object CallPopupTracker {
    private const val PREFS = "callreport_popup_tracker"
    private const val KEY_OPEN = "open"
    private const val KEY_NUMBER = "number"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_OPENED_AT = "opened_at"
    private const val OPEN_TTL_MS = 2 * 60 * 60 * 1000L

    fun markPopupOpened(context: Context, number: String, direction: String) {
        if (number.isBlank()) {
            return
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OPEN, true)
            .putString(KEY_NUMBER, number)
            .putString(KEY_DIRECTION, direction)
            .putLong(KEY_OPENED_AT, System.currentTimeMillis())
            .apply()
    }

    fun markPopupClosed(context: Context, number: String, direction: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trackedNumber = prefs.getString(KEY_NUMBER, "").orEmpty()
        val trackedDirection = prefs.getString(KEY_DIRECTION, "").orEmpty()

        if (sameNumber(trackedNumber, number) && (trackedDirection.isBlank() || direction.isBlank() || trackedDirection == direction)) {
            prefs.edit().clear().apply()
        }
    }

    fun isPopupOpenFor(context: Context, number: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isOpen = prefs.getBoolean(KEY_OPEN, false)
        val trackedNumber = prefs.getString(KEY_NUMBER, "").orEmpty()
        val openedAt = prefs.getLong(KEY_OPENED_AT, 0L)

        if (!isOpen || trackedNumber.isBlank()) {
            return false
        }
        if (System.currentTimeMillis() - openedAt > OPEN_TTL_MS) {
            prefs.edit().clear().apply()
            return false
        }

        return sameNumber(trackedNumber, number)
    }

    private fun sameNumber(first: String, second: String): Boolean {
        val normalizedFirst = normalizeNumber(first)
        val normalizedSecond = normalizeNumber(second)
        return normalizedFirst.isNotBlank() && normalizedFirst == normalizedSecond
    }

    private fun normalizeNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}

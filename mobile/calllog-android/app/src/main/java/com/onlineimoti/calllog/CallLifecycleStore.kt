package com.onlineimoti.calllog

import android.content.Context

internal data class ActiveCallSnapshot(
    val number: String,
    val direction: String,
    val startedAt: Long,
)

internal object CallLifecycleStore {
    private const val PREFS = "callreport_call_lifecycle"
    private const val KEY_NUMBER = "number"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_STARTED_AT = "started_at"
    private const val ACTIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

    fun markActive(context: Context, number: String, direction: String) {
        if (number.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NUMBER, number)
            .putString(KEY_DIRECTION, direction)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun takeEndedCall(context: Context): ActiveCallSnapshot? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val number = prefs.getString(KEY_NUMBER, "").orEmpty()
        val direction = prefs.getString(KEY_DIRECTION, "").orEmpty()
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        prefs.edit().clear().apply()
        if (number.isBlank()) return null
        return ActiveCallSnapshot(number, direction, startedAt)
    }

    fun isActive(context: Context, number: String): Boolean {
        if (number.isBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeNumber = prefs.getString(KEY_NUMBER, "").orEmpty()
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        if (activeNumber.isBlank() || startedAt <= 0L) return false
        if (System.currentTimeMillis() - startedAt > ACTIVE_WINDOW_MS) return false
        return samePhone(activeNumber, number)
    }

    private fun samePhone(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.endsWith(b) || b.endsWith(a)
    }

    private fun normalize(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}

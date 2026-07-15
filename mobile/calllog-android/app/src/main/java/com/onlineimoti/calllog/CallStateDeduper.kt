package com.onlineimoti.calllog

import android.content.Context

internal object CallStateDeduper {
    private const val PREFS = "calllog_call_state_deduper"
    private const val KEY_LAST_NUMBER = "last_number"
    private const val KEY_LAST_DIRECTION = "last_direction"
    private const val KEY_LAST_AT = "last_at"
    private const val WINDOW_MS = 15_000L

    fun wasRecentlyHandled(context: Context, number: String, direction: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAt = prefs.getLong(KEY_LAST_AT, 0L)
        val lastNumber = prefs.getString(KEY_LAST_NUMBER, "").orEmpty()
        val lastDirection = prefs.getString(KEY_LAST_DIRECTION, "").orEmpty()
        return lastDirection == direction &&
            normalizeNumber(lastNumber) == normalizeNumber(number) &&
            System.currentTimeMillis() - lastAt < WINDOW_MS
    }

    fun markHandled(context: Context, number: String, direction: String): Boolean {
        if (wasRecentlyHandled(context, number, direction)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_NUMBER, number)
            .putString(KEY_LAST_DIRECTION, direction)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
        return true
    }

    private fun normalizeNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}

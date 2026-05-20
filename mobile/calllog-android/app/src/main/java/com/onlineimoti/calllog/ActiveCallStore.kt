package com.onlineimoti.calllog

import android.content.Context

data class ActiveCallSession(
    val number: String,
    val direction: String,
    val startedAtMs: Long,
)

object ActiveCallStore {
    private const val PREFS = "calllog_active_call"
    private const val KEY_NUMBER = "number"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_LAST_PROMPT_KEY = "last_prompt_key"

    fun recordStarted(context: Context, number: String, direction: String) {
        if (number.isBlank()) {
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NUMBER, number.trim())
            .putString(KEY_DIRECTION, direction.trim())
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun peek(context: Context): ActiveCallSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val number = prefs.getString(KEY_NUMBER, "").orEmpty().trim()
        val direction = prefs.getString(KEY_DIRECTION, "").orEmpty().trim()
        val startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L)
        if (number.isBlank() || direction.isBlank() || startedAtMs <= 0L) {
            return null
        }
        return ActiveCallSession(number, direction, startedAtMs)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NUMBER)
            .remove(KEY_DIRECTION)
            .remove(KEY_STARTED_AT)
            .apply()
    }

    fun shouldPromptFor(context: Context, call: RecentCallItem): Boolean {
        val promptKey = promptKey(call)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PROMPT_KEY, "").orEmpty() != promptKey
    }

    fun markPrompted(context: Context, call: RecentCallItem) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PROMPT_KEY, promptKey(call))
            .apply()
    }

    private fun promptKey(call: RecentCallItem): String {
        return "${call.id}:${call.callDateMs}:${phoneLastDigits(call.number)}"
    }
}

package com.onlineimoti.calllog

import android.content.Context

internal data class CallLogOverlayButtonSettings(
    val enabled: Boolean,
    val position: String,
)

internal data class CallLogOverlaySavedPosition(
    val x: Int,
    val y: Int,
)

internal object CallLogOverlaySettings {
    private const val PREFS = "call_log_overlay_button"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_POSITION = "position"
    private const val KEY_SAVED_X = "saved_x"
    private const val KEY_SAVED_Y = "saved_y"
    private const val KEY_HAS_SAVED_POSITION = "has_saved_position"
    private const val KEY_EXPECTED_UNTIL_MS = "expected_until_ms"
    private const val EXPECTED_WINDOW_MS = 300_000L

    const val POSITION_TOP_END = "top_end"
    const val POSITION_TOP_START = "top_start"
    const val POSITION_BOTTOM_END = "bottom_end"
    const val POSITION_BOTTOM_START = "bottom_start"
    const val DEFAULT_POSITION = POSITION_TOP_END
    const val DEFAULT_ENABLED = true

    fun load(context: Context): CallLogOverlayButtonSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CallLogOverlayButtonSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            position = normalizePosition(prefs.getString(KEY_POSITION, DEFAULT_POSITION).orEmpty()),
        )
    }

    fun save(context: Context, settings: CallLogOverlayButtonSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_POSITION, normalizePosition(settings.position))
            .apply()
    }

    fun loadSavedPosition(context: Context): CallLogOverlaySavedPosition? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_HAS_SAVED_POSITION, false)) return null
        return CallLogOverlaySavedPosition(
            x = prefs.getInt(KEY_SAVED_X, 0),
            y = prefs.getInt(KEY_SAVED_Y, 0),
        )
    }

    fun saveDraggedPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SAVED_POSITION, true)
            .putInt(KEY_SAVED_X, x)
            .putInt(KEY_SAVED_Y, y)
            .apply()
    }

    fun clearDraggedPosition(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_SAVED_POSITION, false)
            .remove(KEY_SAVED_X)
            .remove(KEY_SAVED_Y)
            .apply()
    }

    fun markExpectedCallLogWindow(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_EXPECTED_UNTIL_MS, System.currentTimeMillis() + EXPECTED_WINDOW_MS)
            .apply()
    }

    fun isExpectedCallLogWindow(context: Context): Boolean {
        val until = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EXPECTED_UNTIL_MS, 0L)
        return System.currentTimeMillis() <= until
    }

    fun normalizePosition(value: String): String {
        return when (value.trim()) {
            POSITION_TOP_START -> POSITION_TOP_START
            POSITION_BOTTOM_END -> POSITION_BOTTOM_END
            POSITION_BOTTOM_START -> POSITION_BOTTOM_START
            else -> POSITION_TOP_END
        }
    }
}

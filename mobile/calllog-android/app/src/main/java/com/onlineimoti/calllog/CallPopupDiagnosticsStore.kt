package com.onlineimoti.calllog

import android.content.Context
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CallPopupDiagnosticsStore {
    private const val PREFS = "call_popup_diagnostics"
    private const val KEY_PHONE_AT = "phone_at"
    private const val KEY_PHONE_STATE = "phone_state"
    private const val KEY_PHONE_NUMBER = "phone_number"
    private const val KEY_PHONE_HANDLED = "phone_handled"
    private const val KEY_PHONE_REASON = "phone_reason"
    private const val KEY_SCREENING_AT = "screening_at"
    private const val KEY_SCREENING_NUMBER = "screening_number"
    private const val KEY_SCREENING_DIRECTION = "screening_direction"
    private const val KEY_SCREENING_HANDLED = "screening_handled"
    private const val KEY_SCREENING_REASON = "screening_reason"

    data class Snapshot(
        val phoneAt: Long = 0L,
        val phoneState: String = "",
        val phoneNumber: String = "",
        val phoneHandled: Boolean = false,
        val phoneReason: String = "",
        val screeningAt: Long = 0L,
        val screeningNumber: String = "",
        val screeningDirection: String = "",
        val screeningHandled: Boolean = false,
        val screeningReason: String = "",
    )

    fun recordPhoneState(context: Context, state: String, number: String, handled: Boolean, reason: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PHONE_AT, System.currentTimeMillis())
            .putString(KEY_PHONE_STATE, state.trim())
            .putString(KEY_PHONE_NUMBER, number.trim())
            .putBoolean(KEY_PHONE_HANDLED, handled)
            .putString(KEY_PHONE_REASON, reason.trim())
            .apply()
    }

    fun recordCallScreening(context: Context, number: String, direction: String, handled: Boolean, reason: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SCREENING_AT, System.currentTimeMillis())
            .putString(KEY_SCREENING_NUMBER, number.trim())
            .putString(KEY_SCREENING_DIRECTION, direction.trim())
            .putBoolean(KEY_SCREENING_HANDLED, handled)
            .putString(KEY_SCREENING_REASON, reason.trim())
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            phoneAt = prefs.getLong(KEY_PHONE_AT, 0L),
            phoneState = prefs.getString(KEY_PHONE_STATE, "").orEmpty(),
            phoneNumber = prefs.getString(KEY_PHONE_NUMBER, "").orEmpty(),
            phoneHandled = prefs.getBoolean(KEY_PHONE_HANDLED, false),
            phoneReason = prefs.getString(KEY_PHONE_REASON, "").orEmpty(),
            screeningAt = prefs.getLong(KEY_SCREENING_AT, 0L),
            screeningNumber = prefs.getString(KEY_SCREENING_NUMBER, "").orEmpty(),
            screeningDirection = prefs.getString(KEY_SCREENING_DIRECTION, "").orEmpty(),
            screeningHandled = prefs.getBoolean(KEY_SCREENING_HANDLED, false),
            screeningReason = prefs.getString(KEY_SCREENING_REASON, "").orEmpty(),
        )
    }

    fun summary(context: Context, config: AppConfig, callScreeningHeld: Boolean, callScreeningAvailable: Boolean): String {
        val snapshot = snapshot(context)
        val lines = mutableListOf<String>()
        lines += "Popup диагностика"
        lines += "PHONE_STATE: ${eventLine(snapshot.phoneAt, snapshot.phoneState, snapshot.phoneNumber, snapshot.phoneHandled, snapshot.phoneReason)}"
        lines += "Caller ID/спам роля: ${when {
            !callScreeningAvailable -> "не се поддържа от телефона"
            callScreeningHeld -> "Relationship Manager е активен"
            else -> "не е при нас — друг app може да е активен"
        }}"
        lines += "Call screening event: ${eventLine(snapshot.screeningAt, snapshot.screeningDirection, snapshot.screeningNumber, snapshot.screeningHandled, snapshot.screeningReason)}"
        lines += "Overlay: ${if (config.useOverlayPopups) "включен" else "изключен"}, draw-over-apps: ${if (Settings.canDrawOverlays(context)) "allow" else "missing"}, start: ${onOff(config.useCustomStartPopup)}, end: ${onOff(config.useCustomEndPopup)}"
        lines += "Server session: ${if (CorporateAccess.isActive(context)) "active" else "inactive"}"
        return lines.joinToString("\n")
    }

    private fun eventLine(at: Long, state: String, number: String, handled: Boolean, reason: String): String {
        if (at <= 0L) return "няма записано събитие"
        val status = if (handled) "OK" else "BLOCK"
        val numberText = maskedNumber(number)
        val stateText = state.ifBlank { "?" }
        val reasonText = reason.ifBlank { "без причина" }
        return "${formatTime(at)} • $status • $stateText • $numberText • $reasonText"
    }

    private fun maskedNumber(number: String): String {
        val digits = number.filter(Char::isDigit)
        if (digits.isBlank()) return "номерът е празен"
        return "…${digits.takeLast(6)}"
    }

    private fun formatTime(value: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"
}

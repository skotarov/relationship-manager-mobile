package com.onlineimoti.calllog

import android.content.Intent
import android.telephony.TelephonyManager
import android.view.WindowManager

/**
 * Isolates Android APIs that have no behavior-identical replacement on older devices.
 * Callers use these helpers instead of spreading deprecated API references through UI code.
 */
internal object LegacyPlatformCompat {
    fun incomingPhoneNumber(intent: Intent): String = legacyIncomingPhoneNumber(intent)

    fun resizeInputMode(alwaysShowKeyboard: Boolean = true): Int = legacyResizeInputMode(alwaysShowKeyboard)

    @Suppress("DEPRECATION")
    private fun legacyIncomingPhoneNumber(intent: Intent): String {
        return intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().trim()
    }

    @Suppress("DEPRECATION")
    private fun legacyResizeInputMode(alwaysShowKeyboard: Boolean): Int {
        val state = if (alwaysShowKeyboard) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        return WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or state
    }
}

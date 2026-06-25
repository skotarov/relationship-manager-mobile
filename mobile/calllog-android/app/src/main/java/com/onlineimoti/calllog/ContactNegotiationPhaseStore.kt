package com.onlineimoti.calllog

import android.content.Context

/** Persists one optional negotiation phase for every normalized contact phone number. */
internal object ContactNegotiationPhaseStore {
    const val NONE = 0
    const val PHASE_1 = 1
    const val PHASE_2 = 2
    const val PHASE_3 = 3
    const val PHASE_4 = 4

    private const val PREFS_NAME = "contact_negotiation_phases"
    private const val KEY_PREFIX = "phase_"

    fun selectedPhase(context: Context, phone: String): Int {
        val key = phoneKey(phone) ?: return NONE
        val stored = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX + key, NONE)
        return stored.takeIf { it in PHASE_1..PHASE_4 } ?: NONE
    }

    fun togglePhase(context: Context, phone: String, phase: Int): Int {
        require(phase in PHASE_1..PHASE_4) { "Unsupported negotiation phase." }
        val key = phoneKey(phone) ?: return NONE
        val current = selectedPhase(context, phone)
        val next = if (current == phase) NONE else phase
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFIX + key, next)
            .apply()
        return next
    }

    private fun phoneKey(phone: String): String? {
        return HomeCallPageLoader.noteKey(phone).takeIf { it.isNotBlank() }
    }
}

package com.onlineimoti.calllog

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            return
        }

        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState || !hasCallLog) {
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().trim()

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            handleCallEnded(context)
            return
        }

        if (number.isBlank()) {
            return
        }

        val direction = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "in"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (CallStateDeduper.wasRecentlyHandled(context, number, "in")) {
                    CallLifecycleStore.markActive(context, number, "in")
                    return
                }
                "out"
            }
            else -> return
        }

        CallLifecycleStore.markActive(context, number, direction)

        if (!CallStateDeduper.markHandled(context, number, direction)) {
            return
        }

        showLookup(context, number, direction, fullscreen = direction == "in")
    }

    private fun handleCallEnded(context: Context) {
        val endedCall = CallLifecycleStore.takeEndedCall(context) ?: return
        if (!CallStateDeduper.markHandled(context, endedCall.number, "${endedCall.direction}_ended")) {
            return
        }

        showPostCallPrompt(
            context = context,
            number = endedCall.number,
            direction = endedCall.direction,
        )
    }

    private fun showLookup(context: Context, number: String, direction: String, fullscreen: Boolean) {
        val pendingResult = goAsync()
        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(context)
                if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
                    return@execute
                }
                if (!ContactGroupFilter.shouldNotify(context, number, config)) {
                    return@execute
                }
                val displayName = ContactGroupFilter.resolveDisplayName(context, number)

                CallReportRuntime.ensureNotificationChannel(context)
                val result = CallReportRuntime.fetchLookup(config, number, direction).let { lookup ->
                    if (displayName.isNullOrBlank()) {
                        lookup
                    } else {
                        lookup.copy(title = displayName)
                    }
                }
                CallReportRuntime.showLookupNotification(
                    context = context,
                    result = result,
                    fullscreen = fullscreen,
                    phone = number,
                    direction = direction,
                )
            } catch (_: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showPostCallPrompt(context: Context, number: String, direction: String) {
        val pendingResult = goAsync()
        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(context)
                if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
                    return@execute
                }
                if (!ContactGroupFilter.shouldNotify(context, number, config)) {
                    return@execute
                }
                val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
                val result = CallReportRuntime.fetchLookup(config, number, direction).let { lookup ->
                    if (displayName.isBlank()) {
                        lookup
                    } else {
                        lookup.copy(title = displayName)
                    }
                }

                CallReportRuntime.showPostCallPromptNotification(
                    context = context,
                    formUrl = result.openFormUrl,
                    phone = number,
                    direction = direction,
                    title = result.title,
                )
            } catch (_: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    private object CallStateDeduper {
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
            if (wasRecentlyHandled(context, number, direction)) {
                return false
            }

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

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

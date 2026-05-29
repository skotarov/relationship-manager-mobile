package com.onlineimoti.calllog

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState || !hasCallLog) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().trim()

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            handleCallEnded(context)
            return
        }
        if (number.isBlank()) return

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
        if (!CallStateDeduper.markHandled(context, number, direction)) return
        showInstantLoading(context, number, "Зарежда се информация…", "Проверявам разговори и бележка…")
        showLookup(context, number, direction, fullscreen = direction == "in")
    }

    private fun handleCallEnded(context: Context) {
        val endedCall = CallLifecycleStore.takeEndedCall(context) ?: return
        if (!CallStateDeduper.markHandled(context, endedCall.number, "${endedCall.direction}_ended")) return
        showPostCallPrompt(context, endedCall.number, endedCall.direction)
    }

    private fun showInstantLoading(context: Context, number: String, title: String, subtitle: String) {
        val config = ConfigStore.load(context)
        if (!config.useOverlayPopups || !config.useCustomStartPopup || !Settings.canDrawOverlays(context) || isScreenLocked(context)) return
        context.startService(
            Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_LOADING)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, number)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, subtitle)
        )
    }

    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isKeyguardLocked == true
    }

    private fun remoteReady(config: AppConfig): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }

    private fun showLookup(context: Context, number: String, direction: String, fullscreen: Boolean) {
        val pendingResult = goAsync()
        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(context)
                if (!ContactGroupFilter.shouldNotify(context, number, config)) return@execute
                val displayName = ContactGroupFilter.resolveDisplayName(context, number)
                val title = displayName.ifNullOrBlank { number }

                CallReportRuntime.ensureNotificationChannel(context)

                if (!remoteReady(config)) {
                    LookupPopupPresenter.show(
                        context = context,
                        result = LookupResult(
                            title = title,
                            subtitle = "Локален режим — без сървърни данни",
                            lines = emptyList(),
                            openFormUrl = "",
                        ),
                        fullscreen = fullscreen,
                        phone = number,
                        direction = direction,
                    )
                    return@execute
                }

                LookupPopupPresenter.show(
                    context = context,
                    result = LookupResult(
                        title = title,
                        subtitle = "Зарежда се информация от Call Report…",
                        lines = emptyList(),
                        openFormUrl = "",
                    ),
                    fullscreen = fullscreen,
                    phone = number,
                    direction = direction,
                )

                val result = CallReportRuntime.fetchLookup(config, number, direction).let { lookup ->
                    if (displayName.isNullOrBlank()) lookup else lookup.copy(title = displayName)
                }
                LookupPopupPresenter.show(
                    context = context,
                    result = result,
                    fullscreen = false,
                    phone = number,
                    direction = direction,
                )
            } catch (_: Throwable) {
                CallReportRuntime.ensureNotificationChannel(context)
                LookupPopupPresenter.show(
                    context = context,
                    result = LookupResult(
                        title = number,
                        subtitle = "Локален режим",
                        lines = emptyList(),
                        openFormUrl = "",
                    ),
                    fullscreen = fullscreen,
                    phone = number,
                    direction = direction,
                )
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
                if (!ContactGroupFilter.shouldNotify(context, number, config)) return@execute
                if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) return@execute

                if (!config.useOverlayPopups || !config.useCustomEndPopup || !Settings.canDrawOverlays(context)) {
                    showSystemPostCallAction(context, number, direction, config)
                    return@execute
                }

                if (!remoteReady(config)) {
                    CallReportRuntime.showImmediatePostCallPrompt(
                        context = context,
                        formUrl = "",
                        phone = number,
                        direction = direction,
                        title = "Локални действия след разговора",
                    )
                    return@execute
                }

                val fallbackFormUrl = buildEndpoint(
                    baseUrl = config.baseUrl,
                    path = config.formPath,
                    params = linkedMapOf(
                        "phone" to number,
                        "direction" to direction,
                        "access_token" to config.accessToken,
                    )
                )
                CallReportRuntime.showImmediatePostCallPrompt(
                    context = context,
                    formUrl = fallbackFormUrl,
                    phone = number,
                    direction = direction,
                    title = "Бележка след разговора",
                )

                val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
                val result = CallReportRuntime.fetchLookup(config, number, direction).let { lookup ->
                    if (displayName.isBlank()) lookup else lookup.copy(title = displayName)
                }

                CallReportRuntime.showPostCallPromptNotification(
                    context = context,
                    formUrl = result.openFormUrl.ifBlank { fallbackFormUrl },
                    phone = number,
                    direction = direction,
                    title = result.title,
                )
            } catch (_: Throwable) {
                val config = ConfigStore.load(context)
                if (config.postCallEndAction != ConfigStore.POST_CALL_END_ACTION_NOTHING) {
                    showSystemPostCallAction(context, number, direction, config)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showSystemPostCallAction(context: Context, number: String, direction: String, config: AppConfig) {
        when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> openFullscreenHistory(context, number)
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> return
            else -> openFullscreenNoteEditor(context, number, direction)
        }
    }

    private fun openFullscreenHistory(context: Context, number: String) {
        val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
        context.startActivity(
            Intent(context, ContactNotesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, number)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, displayName.ifBlank { number.ifBlank { "История" } })
        )
    }

    private fun openFullscreenNoteEditor(context: Context, number: String, direction: String) {
        val latestCall = PhoneCallReader.callsForPhone(context, number, limit = 1).firstOrNull()
        val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
        context.startActivity(
            Intent(context, ContactNoteEditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, number)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction.ifBlank { latestCall?.direction.orEmpty() })
                .putExtra(PostCallOverlayService.EXTRA_TITLE, displayName.ifBlank { number.ifBlank { "Бележка от разговора" } })
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, latestCall?.startedAt ?: 0L)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, latestCall?.durationSeconds ?: 0L)
        )
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
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
            return lastDirection == direction && normalizeNumber(lastNumber) == normalizeNumber(number) && System.currentTimeMillis() - lastAt < WINDOW_MS
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

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

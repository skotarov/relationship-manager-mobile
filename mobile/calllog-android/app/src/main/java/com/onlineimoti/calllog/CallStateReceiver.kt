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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        // Do not turn the Play app into a free personal call tracker. Call-state
        // processing starts only after the user has a signed-in company CRM session.
        if (!CorporateAccess.isActive(context)) return

        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty()
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty().trim()

        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            handleCallEnded(context, hasCallLog)
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
        val config = ConfigStore.load(context.applicationContext)
        showInstantLoading(context, config, number, "Зарежда се информация…", "Проверявам разговори и бележка…")
        showLookup(context, config, number, direction, fullscreen = direction == "in")
    }

    private fun handleCallEnded(context: Context, hasCallLog: Boolean) {
        val endedCall = CallLifecycleStore.takeEndedCall(context) ?: latestEndedCallFromLog(context, hasCallLog) ?: return
        if (!CallStateDeduper.markHandled(context, endedCall.number, "${endedCall.direction}_ended")) return
        // The CallLog row may arrive shortly after PHONE_STATE_IDLE. The worker waits and then reads it.
        CallReportSyncScheduler.enqueueCatchUp(context.applicationContext, reason = "call_ended", initialDelayMillis = 2_500L)
        showPostCallPrompt(context, endedCall.number, endedCall.direction)
    }

    private fun latestEndedCallFromLog(context: Context, hasCallLog: Boolean): ActiveCallRecord? {
        if (!hasCallLog) return null
        val latest = PhoneCallReader.latestCall(context) ?: return null
        val now = System.currentTimeMillis()
        val endedAt = latest.startedAt + latest.durationSeconds.coerceAtLeast(0L) * 1000L
        if (latest.number.isBlank() || latest.startedAt <= 0L) return null
        if (endedAt > now + 60_000L) return null
        if (now - endedAt > 2 * 60_000L) return null
        return ActiveCallRecord(number = latest.number, direction = latest.direction, startedAt = latest.startedAt)
    }

    private fun showInstantLoading(
        context: Context,
        config: AppConfig,
        number: String,
        title: String,
        subtitle: String,
    ) {
        if (!config.useOverlayPopups || !config.useCustomStartPopup || !Settings.canDrawOverlays(context) || isScreenLocked(context)) return
        context.startService(
            Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_LOADING)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, number)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, subtitle),
        )
    }

    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isKeyguardLocked == true
    }

    private fun remoteReady(config: AppConfig): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }

    private fun showLookup(
        context: Context,
        config: AppConfig,
        number: String,
        direction: String,
        fullscreen: Boolean,
    ) {
        val pendingResult = goAsync()
        IncomingCallLookupCoordinator(
            context = context.applicationContext,
            config = config,
            phone = number,
            direction = direction,
            fullscreen = fullscreen,
            onLookupFinished = { pendingResult.finish() },
        ).start()
    }

    private fun showPostCallPrompt(context: Context, number: String, direction: String) {
        val pendingResult = goAsync()
        executeBounded(pendingResult) {
            try {
                val config = ConfigStore.load(context)
                if (!ContactGroupFilter.shouldNotify(context, number, config)) return@executeBounded
                if (config.postCallEndAction == ConfigStore.POST_CALL_END_ACTION_NOTHING) return@executeBounded

                if (!remoteReady(config)) {
                    if (config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(context)) {
                        CallReportRuntime.showImmediatePostCallPrompt(
                            context = context,
                            formUrl = "",
                            phone = number,
                            direction = direction,
                            title = "Локални действия след разговора",
                        )
                    } else {
                        routeLocalPostCall(context, number, direction, config)
                    }
                    return@executeBounded
                }

                val lookupContext = CallReportSyncEventFactory.latestPhoneCallContext(context, number, direction)
                val fallbackParams = linkedMapOf(
                    "phone" to number,
                    "direction" to direction,
                    "access_token" to config.accessToken,
                )
                fallbackParams.putAll(lookupContext.asQueryParameters())
                val fallbackFormUrl = buildEndpoint(config.baseUrl, config.formPath, fallbackParams)
                val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
                val result = runCatching {
                    CallReportRuntime.fetchLookup(config, number, direction, lookupContext).let { lookup ->
                        if (displayName.isBlank()) lookup else lookup.copy(title = displayName)
                    }
                }.getOrElse {
                    LookupResult(displayName.ifBlank { "Бележка след разговора" }, number, emptyList(), fallbackFormUrl)
                }
                CallReportRuntime.showPostCallPromptNotification(
                    context = context,
                    formUrl = result.openFormUrl.ifBlank { fallbackFormUrl },
                    phone = number,
                    direction = direction,
                    title = result.title.ifBlank { "Бележка след разговора" },
                )
            } catch (_: Throwable) {
                val config = ConfigStore.load(context)
                if (config.postCallEndAction != ConfigStore.POST_CALL_END_ACTION_NOTHING) {
                    routeLocalPostCall(context, number, direction, config)
                }
            }
        }
    }

    /**
     * Prevents a bad/slow server from retaining an unbounded list of BroadcastReceiver tasks.
     * A rejected new lookup is deliberately skipped; older accepted work is allowed to finish.
     */
    private fun executeBounded(pendingResult: PendingResult, block: () -> Unit) {
        try {
            EXECUTOR.execute {
                try {
                    block()
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingResult.finish()
        }
    }

    private fun routeLocalPostCall(context: Context, number: String, direction: String, config: AppConfig) {
        val displayName = ContactGroupFilter.resolveDisplayName(context, number).orEmpty()
        val title = when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> displayName.ifBlank { number.ifBlank { "История" } }
            else -> displayName.ifBlank { number.ifBlank { "Бележка след разговора" } }
        }
        PostCallActionRouter.route(context, number, direction, title, config = config)
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

    private companion object {
        const val MAX_PENDING_LOOKUPS = 12
        val EXECUTOR = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_PENDING_LOOKUPS),
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

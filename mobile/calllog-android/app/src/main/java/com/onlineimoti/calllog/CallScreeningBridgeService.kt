package com.onlineimoti.calllog

import android.net.Uri
import android.telecom.Call
import android.telecom.CallScreeningService
import java.util.concurrent.Executors

class CallScreeningBridgeService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        respondToCall(
            callDetails,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
        )

        val handle: Uri = callDetails.handle ?: return
        val number = handle.schemeSpecificPart?.trim().orEmpty()
        if (number.isBlank()) {
            return
        }

        val direction = when (callDetails.callDirection) {
            Call.Details.DIRECTION_INCOMING -> "in"
            Call.Details.DIRECTION_OUTGOING -> "out"
            else -> return
        }

        CallLifecycleStore.markActive(this, number, direction)

        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(this)
                if (!ContactGroupFilter.shouldNotify(this, number, config)) {
                    return@execute
                }
                val displayName = ContactGroupFilter.resolveDisplayName(this, number)
                val title = displayName.ifNullOrBlank { number }

                CallReportRuntime.ensureNotificationChannel(this)
                if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
                    LookupPopupPresenter.show(
                        context = this,
                        result = LookupResult(
                            title = title,
                            subtitle = "Локален режим — няма access token",
                            lines = emptyList(),
                            openFormUrl = "",
                        ),
                        fullscreen = direction == "in",
                        phone = number,
                        direction = direction,
                    )
                    return@execute
                }

                val result = CallReportRuntime.fetchLookup(config, number, direction).let { lookup ->
                    if (displayName.isNullOrBlank()) {
                        lookup
                    } else {
                        lookup.copy(title = displayName)
                    }
                }
                LookupPopupPresenter.show(
                    context = this,
                    result = result,
                    fullscreen = direction == "in",
                    phone = number,
                    direction = direction,
                )
            } catch (_: Throwable) {
                CallReportRuntime.ensureNotificationChannel(this)
                LookupPopupPresenter.show(
                    context = this,
                    result = LookupResult(
                        title = number,
                        subtitle = "Локален режим",
                        lines = emptyList(),
                        openFormUrl = "",
                    ),
                    fullscreen = direction == "in",
                    phone = number,
                    direction = direction,
                )
            }
        }
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

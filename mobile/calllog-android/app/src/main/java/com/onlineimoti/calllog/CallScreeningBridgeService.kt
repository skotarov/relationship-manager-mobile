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

        // The service remains neutral for everyone. CRM processing begins only
        // after the device has a signed-in company session.
        if (!CorporateAccess.isActive(this)) {
            CallPopupDiagnosticsStore.recordCallScreening(
                context = this,
                number = callDetails.handle?.schemeSpecificPart.orEmpty(),
                direction = callDetails.callDirection.toString(),
                handled = false,
                reason = "няма активна сървърна/корпоративна сесия",
            )
            return
        }

        val handle: Uri = callDetails.handle ?: run {
            CallPopupDiagnosticsStore.recordCallScreening(this, "", callDetails.callDirection.toString(), false, "празен call handle")
            return
        }
        val number = handle.schemeSpecificPart?.trim().orEmpty()
        if (number.isBlank()) {
            CallPopupDiagnosticsStore.recordCallScreening(this, number, callDetails.callDirection.toString(), false, "празен номер от CallScreeningService")
            return
        }

        val direction = when (callDetails.callDirection) {
            Call.Details.DIRECTION_INCOMING -> "in"
            Call.Details.DIRECTION_OUTGOING -> "out"
            else -> {
                CallPopupDiagnosticsStore.recordCallScreening(this, number, callDetails.callDirection.toString(), false, "неподдържана посока")
                return
            }
        }

        CallPopupDiagnosticsStore.recordCallScreening(
            context = this,
            number = number,
            direction = direction,
            handled = true,
            reason = "получено от активната Caller ID/спам роля",
        )
        CallLifecycleStore.markActive(this, number, direction)

        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(this)
                if (!ContactGroupFilter.shouldNotify(this, number, config)) return@execute
                val displayName = ContactGroupFilter.resolveDisplayName(this, number)
                val title = displayName.ifNullOrBlank { number }

                CallReportRuntime.ensureNotificationChannel(this)
                if (!remoteReady(config)) {
                    LookupPopupPresenter.show(
                        context = this,
                        result = LookupResult(
                            title = title,
                            subtitle = "Локален режим — без сървърни данни",
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
                    if (displayName.isNullOrBlank()) lookup else lookup.copy(title = displayName)
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

    private fun remoteReady(config: AppConfig): Boolean {
        return config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

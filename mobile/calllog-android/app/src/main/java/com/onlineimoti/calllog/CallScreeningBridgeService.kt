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

        EXECUTOR.execute {
            try {
                val config = ConfigStore.load(this)
                if (config.baseUrl.isBlank() || config.accessToken.isBlank()) {
                    return@execute
                }
                if (!ContactGroupFilter.shouldNotify(this, number, config)) {
                    return@execute
                }

                CallReportRuntime.ensureNotificationChannel(this)
                val result = CallReportRuntime.fetchLookup(config, number, direction)
                CallReportRuntime.showLookupNotification(
                    context = this,
                    result = result,
                    fullscreen = direction == "in"
                )
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}

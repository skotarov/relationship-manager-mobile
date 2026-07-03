package com.onlineimoti.calllog

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

object LookupPopupPresenter {
    fun show(
        context: Context,
        result: LookupResult,
        fullscreen: Boolean = false,
        phone: String = "",
        direction: String = "",
        /** Incoming-call coordinator already requested history rows in parallel. */
        remoteRowsArePreloaded: Boolean = false,
    ) {
        val config = ConfigStore.load(context)
        val screenLocked = isScreenLocked(context)
        if (config.useOverlayPopups && config.useCustomStartPopup && Settings.canDrawOverlays(context) && !screenLocked) {
            // Start the actual overlay before the passive shade notification.
            // Notification rendering can read Call Log/Contacts, while the overlay
            // now receives all expensive data from background caches.
            context.startService(
                Intent(context, PostCallOverlayService::class.java)
                    .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_LOOKUP)
                    .putExtra(PostCallOverlayService.EXTRA_TITLE, result.title)
                    .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, result.subtitle)
                    .putStringArrayListExtra(PostCallOverlayService.EXTRA_LINES, ArrayList(result.lines))
                    .putExtra(PostCallOverlayService.EXTRA_FORM_URL, result.openFormUrl)
                    .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                    .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                    .putExtra(PostCallOverlayService.EXTRA_REMOTE_ROWS_ARE_PRELOADED, remoteRowsArePreloaded),
            )
            CallReportRuntime.showLookupShadeNotification(
                context = context,
                result = result,
                phone = phone,
                direction = direction,
                incomingPopupDataIsPreloaded = remoteRowsArePreloaded,
            )
            if (phone.isNotBlank()) {
                CallPopupTracker.markPopupOpened(context, phone, direction)
            }
            return
        }

        CallReportRuntime.showLookupNotification(
            context = context,
            result = result,
            fullscreen = fullscreen && screenLocked,
            phone = phone,
            direction = direction,
        )
    }

    private fun isScreenLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isKeyguardLocked == true
    }
}

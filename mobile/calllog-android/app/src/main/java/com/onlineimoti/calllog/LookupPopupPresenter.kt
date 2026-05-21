package com.onlineimoti.calllog

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
    ) {
        val config = ConfigStore.load(context)
        if (config.useCustomStartPopup && Settings.canDrawOverlays(context)) {
            context.startService(
                Intent(context, PostCallOverlayService::class.java)
                    .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_LOOKUP)
                    .putExtra(PostCallOverlayService.EXTRA_TITLE, result.title)
                    .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, result.subtitle)
                    .putStringArrayListExtra(PostCallOverlayService.EXTRA_LINES, ArrayList(result.lines))
                    .putExtra(PostCallOverlayService.EXTRA_FORM_URL, result.openFormUrl)
                    .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                    .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
            )
            if (phone.isNotBlank()) {
                CallPopupTracker.markPopupOpened(context, phone, direction)
            }
            return
        }

        CallReportRuntime.showLookupNotification(
            context = context,
            result = result,
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
        )
    }
}

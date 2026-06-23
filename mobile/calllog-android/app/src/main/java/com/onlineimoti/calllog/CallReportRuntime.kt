package com.onlineimoti.calllog

import android.content.Context

object CallReportRuntime {
    const val POST_CALL_NOTIFICATION_ID = 2002

    fun ensureNotificationChannel(context: Context) {
        CallReportNotifications.ensureNotificationChannel(context)
    }

    fun ensureContactsSync(context: Context, force: Boolean = false) {
        CallReportContactsSyncBridge.ensureAccountAndRequestSync(context, force)
    }

    internal fun fetchLookup(
        config: AppConfig,
        phone: String,
        direction: String,
        context: CallReportLookupContext = CallReportLookupContext(),
    ): LookupResult {
        return CallReportLookupClient.fetchLookup(config, phone, direction, context)
    }

    fun showLoadingLookupNotification(context: Context, phone: String, direction: String, title: String = "Loading", fullscreen: Boolean = false) {
        CallReportNotifications.showLoadingLookupNotification(context, phone, direction, title, fullscreen)
    }

    fun showLookupNotification(context: Context, result: LookupResult, fullscreen: Boolean = false, phone: String = "", direction: String = "") {
        CallReportNotifications.showLookupNotification(context, result, fullscreen, phone, direction)
    }

    fun showLookupShadeNotification(context: Context, result: LookupResult, phone: String = "", direction: String = "") {
        CallReportNotifications.showLookupShadeNotification(context, result, phone, direction)
    }

    fun showImmediatePostCallPrompt(context: Context, formUrl: String, phone: String, direction: String, title: String = "Post call note") {
        CallReportNotifications.showImmediatePostCallPrompt(context, formUrl, phone, direction, title)
    }

    fun showPostCallPromptNotification(context: Context, formUrl: String, phone: String, direction: String, title: String) {
        CallReportNotifications.showPostCallPromptNotification(context, formUrl, phone, direction, title)
    }
}

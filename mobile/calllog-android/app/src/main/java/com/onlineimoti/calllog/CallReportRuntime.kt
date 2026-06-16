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

    fun fetchLookup(config: AppConfig, phone: String, direction: String): LookupResult {
        return CallReportLookupClient.fetchLookup(config, phone, direction)
    }

    fun showLoadingLookupNotification(
        context: Context,
        phone: String,
        direction: String,
        title: String = "Зарежда се информация…",
        fullscreen: Boolean = false,
    ) {
        CallReportNotifications.showLoadingLookupNotification(
            context = context,
            phone = phone,
            direction = direction,
            title = title,
            fullscreen = fullscreen,
        )
    }

    fun showLookupNotification(
        context: Context,
        result: LookupResult,
        fullscreen: Boolean = false,
        phone: String = "",
        direction: String = "",
    ) {
        CallReportNotifications.showLookupNotification(
            context = context,
            result = result,
            fullscreen = fullscreen,
            phone = phone,
            direction = direction,
        )
    }

    fun showLookupShadeNotification(context: Context, result: LookupResult, phone: String = "", direction: String = "") {
        CallReportNotifications.showLookupShadeNotification(
            context = context,
            result = result,
            phone = phone,
            direction = direction,
        )
    }

    fun showImmediatePostCallPrompt(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String = "Бележка след разговора",
        actionIssuedAt: Long = 0L,
    ) {
        CallReportNotifications.showImmediatePostCallPrompt(
            context = context,
            formUrl = formUrl,
            phone = phone,
            direction = direction,
            title = title,
            actionIssuedAt = actionIssuedAt,
        )
    }

    fun showPostCallPromptNotification(
        context: Context,
        formUrl: String,
        phone: String,
        direction: String,
        title: String,
        actionIssuedAt: Long = 0L,
    ) {
        CallReportNotifications.showPostCallPromptNotification(
            context = context,
            formUrl = formUrl,
            phone = phone,
            direction = direction,
            title = title,
            actionIssuedAt = actionIssuedAt,
        )
    }
}

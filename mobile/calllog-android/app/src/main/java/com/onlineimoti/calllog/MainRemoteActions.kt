package com.onlineimoti.calllog

import android.content.Intent

internal object MainRemoteActions {
    fun openFormDirect(
        activity: MainActivity,
        config: AppConfig,
        phone: String,
        direction: String,
        remoteReady: Boolean,
        setStatus: (String) -> Unit,
    ) {
        if (!remoteReady || phone.isBlank()) {
            setStatus("За server форма включи Сървър и попълни Base URL, access token и телефон.")
            return
        }
        openWebView(activity, buildFormUrl(config, phone, direction))
    }

    fun openFullLogDirect(
        activity: MainActivity,
        config: AppConfig,
        phone: String,
        direction: String,
        remoteReady: Boolean,
        setStatus: (String) -> Unit,
    ) {
        if (!remoteReady || phone.isBlank()) {
            setStatus("За server лог включи Сървър и попълни Base URL, access token и телефон.")
            return
        }
        openWebView(activity, buildHistoryUrl(config, phone, direction))
        setStatus("Отворен е тестов пълен лог.")
    }

    fun buildFormUrl(config: AppConfig, phone: String, direction: String): String = buildEndpoint(
        baseUrl = config.baseUrl,
        path = config.formPath,
        params = linkedMapOf("phone" to phone, "direction" to direction, "access_token" to config.accessToken),
    )

    private fun buildHistoryUrl(config: AppConfig, phone: String, direction: String): String = buildEndpoint(
        baseUrl = config.baseUrl,
        path = config.historyPath,
        params = linkedMapOf("phone" to phone, "direction" to direction, "access_token" to config.accessToken),
    )

    private fun openWebView(activity: MainActivity, url: String) {
        activity.startActivity(Intent(activity, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url))
    }
}

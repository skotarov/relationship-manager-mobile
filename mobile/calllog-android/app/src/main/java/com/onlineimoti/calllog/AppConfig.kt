package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri

data class AppConfig(
    val baseUrl: String,
    val accessToken: String,
    val contactGroups: String,
    val formPath: String,
    val historyPath: String,
    val testPhoneNumber: String,
    val postCallAutoCloseSeconds: Int,
) {
    val resolvedFormPath: String
        get() = normalizePath(formPath.ifBlank { DEFAULT_FORM_PATH })

    val resolvedHistoryPath: String
        get() = normalizePath(historyPath.ifBlank { DEFAULT_HISTORY_PATH })

    val resolvedPostCallAutoCloseSeconds: Int
        get() = postCallAutoCloseSeconds.coerceAtLeast(MIN_POST_CALL_AUTO_CLOSE_SECONDS)

    companion object {
        const val DEFAULT_FORM_PATH = "/broker/callreport/form.php"
        const val DEFAULT_HISTORY_PATH = "/broker/callreport/history.php"
        const val DEFAULT_TEST_PHONE_NUMBER = "0877904903"
        const val DEFAULT_POST_CALL_AUTO_CLOSE_SECONDS = 6
        const val MIN_POST_CALL_AUTO_CLOSE_SECONDS = 1
    }
}

object ConfigStore {
    private const val PREFS = "calllog_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_CONTACT_GROUPS = "contact_groups"
    private const val KEY_FORM_PATH = "form_path"
    private const val KEY_HISTORY_PATH = "history_path"
    private const val KEY_TEST_PHONE_NUMBER = "test_phone_number"
    private const val KEY_POST_CALL_AUTO_CLOSE_SECONDS = "post_call_auto_close_seconds"

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accessToken = if (prefs.contains(KEY_ACCESS_TOKEN)) {
            prefs.getString(KEY_ACCESS_TOKEN, "")!!.trim()
        } else {
            BuildConfig.DEFAULT_ACCESS_TOKEN.trim()
        }

        return AppConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "https://onlineimoti.com")!!.trim(),
            accessToken = accessToken,
            contactGroups = prefs.getString(KEY_CONTACT_GROUPS, "")!!.trim(),
            formPath = prefs.getString(KEY_FORM_PATH, "")!!.trim(),
            historyPath = prefs.getString(KEY_HISTORY_PATH, "")!!.trim(),
            testPhoneNumber = prefs.getString(
                KEY_TEST_PHONE_NUMBER,
                AppConfig.DEFAULT_TEST_PHONE_NUMBER
            )!!.trim(),
            postCallAutoCloseSeconds = prefs.getInt(
                KEY_POST_CALL_AUTO_CLOSE_SECONDS,
                AppConfig.DEFAULT_POST_CALL_AUTO_CLOSE_SECONDS
            ),
        )
    }

    fun save(context: Context, config: AppConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, config.accessToken.trim())
            .putString(KEY_CONTACT_GROUPS, config.contactGroups.trim())
            .putString(KEY_FORM_PATH, config.formPath.trim())
            .putString(KEY_HISTORY_PATH, config.historyPath.trim())
            .putString(KEY_TEST_PHONE_NUMBER, config.testPhoneNumber.trim())
            .putInt(
                KEY_POST_CALL_AUTO_CLOSE_SECONDS,
                config.postCallAutoCloseSeconds.coerceAtLeast(AppConfig.MIN_POST_CALL_AUTO_CLOSE_SECONDS)
            )
            .apply()
    }
}

fun buildEndpoint(baseUrl: String, path: String, params: Map<String, String>): String {
    val base = baseUrl.trim().trimEnd('/')
    val builder = Uri.parse(base + normalizePath(path)).buildUpon().clearQuery()
    params.forEach { (key, value) ->
        if (value.isNotBlank()) {
            builder.appendQueryParameter(key, value)
        }
    }
    return builder.build().toString()
}

fun buildMultiValueEndpoint(baseUrl: String, path: String, params: Map<String, String>, listParams: Map<String, List<String>>): String {
    val base = baseUrl.trim().trimEnd('/')
    val builder = Uri.parse(base + normalizePath(path)).buildUpon().clearQuery()
    params.forEach { (key, value) ->
        if (value.isNotBlank()) {
            builder.appendQueryParameter(key, value)
        }
    }
    listParams.forEach { (key, values) ->
        values.forEach { value ->
            if (value.isNotBlank()) {
                builder.appendQueryParameter(key, value)
            }
        }
    }
    return builder.build().toString()
}

fun AppConfig.buildFormUrl(phone: String, direction: String, extraParams: Map<String, String> = emptyMap()): String {
    return buildEndpoint(
        baseUrl = baseUrl,
        path = resolvedFormPath,
        params = linkedMapOf<String, String>().apply {
            put("phone", phone)
            put("direction", direction)
            put("access_token", accessToken)
            putAll(extraParams)
        }
    )
}

fun AppConfig.buildHistoryUrl(phone: String, direction: String = ""): String {
    return buildEndpoint(
        baseUrl = baseUrl,
        path = resolvedHistoryPath,
        params = linkedMapOf(
            "phone" to phone,
            "direction" to direction,
            "access_token" to accessToken,
        )
    )
}

fun AppConfig.buildHistoryLookupUrl(phones: List<String>): String {
    return buildMultiValueEndpoint(
        baseUrl = baseUrl,
        path = "/broker/callreport/history_lookup.php",
        params = linkedMapOf(
            "access_token" to accessToken,
        ),
        listParams = linkedMapOf(
            "phones[]" to phones,
        )
    )
}

private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) {
        return "/"
    }
    return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
}

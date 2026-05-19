package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri

data class AppConfig(
    val baseUrl: String,
    val accessToken: String,
)

object ConfigStore {
    private const val PREFS = "calllog_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppConfig(
            baseUrl = prefs.getString(KEY_BASE_URL, "https://onlineimoti.com")!!.trim(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, "")!!.trim(),
        )
    }

    fun save(context: Context, config: AppConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, config.accessToken.trim())
            .apply()
    }
}

fun buildEndpoint(baseUrl: String, path: String, params: Map<String, String>): String {
    val base = baseUrl.trim().trimEnd('/')
    val builder = Uri.parse(base + path).buildUpon().clearQuery()
    params.forEach { (key, value) ->
        if (value.isNotBlank()) {
            builder.appendQueryParameter(key, value)
        }
    }
    return builder.build().toString()
}

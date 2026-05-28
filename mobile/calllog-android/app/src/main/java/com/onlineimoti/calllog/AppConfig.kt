package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri

data class AppConfig(
    val remoteEnabled: Boolean,
    val baseUrl: String,
    val accessToken: String,
    val contactGroups: String,
    val notifyUnknownContacts: Boolean,
    val notifyKnownContacts: Boolean,
    val lookupPath: String,
    val formPath: String,
    val historyPath: String,
    val postCallPromptTimeoutSeconds: Int,
    val useCustomStartPopup: Boolean,
    val useCustomEndPopup: Boolean,
    val contactLinkMode: String,
)

object ConfigStore {
    private const val PREFS = "callreport_prefs"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_CONTACT_GROUPS = "contact_groups"
    private const val KEY_NOTIFY_UNKNOWN_CONTACTS = "notify_unknown_contacts"
    private const val KEY_NOTIFY_KNOWN_CONTACTS = "notify_known_contacts"
    private const val KEY_LOOKUP_PATH = "lookup_path"
    private const val KEY_FORM_PATH = "form_path"
    private const val KEY_HISTORY_PATH = "history_path"
    private const val KEY_POST_CALL_TIMEOUT = "post_call_timeout"
    private const val KEY_USE_CUSTOM_START_POPUP = "use_custom_start_popup"
    private const val KEY_USE_CUSTOM_END_POPUP = "use_custom_end_popup"
    private const val KEY_CONTACT_LINK_MODE = "contact_link_mode"

    const val DEFAULT_LOOKUP_PATH = "/broker/callreport/lookup.php"
    const val DEFAULT_FORM_PATH = "/broker/callreport/form.php"
    const val DEFAULT_HISTORY_PATH = "/broker/callreport/history.php"
    const val DEFAULT_POST_CALL_TIMEOUT_SECONDS = 10
    const val CONTACT_LINK_MODE_APP = "app"
    const val CONTACT_LINK_MODE_CONTACT = "contact"
    const val DEFAULT_CONTACT_LINK_MODE = CONTACT_LINK_MODE_APP

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppConfig(
            remoteEnabled = prefs.getBoolean(KEY_REMOTE_ENABLED, false),
            baseUrl = prefs.getString(KEY_BASE_URL, "https://onlineimoti.com")!!.trim(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, BuildConfig.DEFAULT_ACCESS_TOKEN)!!.trim(),
            contactGroups = prefs.getString(KEY_CONTACT_GROUPS, "")!!.trim(),
            notifyUnknownContacts = prefs.getBoolean(KEY_NOTIFY_UNKNOWN_CONTACTS, true),
            notifyKnownContacts = prefs.getBoolean(KEY_NOTIFY_KNOWN_CONTACTS, false),
            lookupPath = normalizePath(prefs.getString(KEY_LOOKUP_PATH, DEFAULT_LOOKUP_PATH)!!.trim(), DEFAULT_LOOKUP_PATH),
            formPath = normalizePath(prefs.getString(KEY_FORM_PATH, DEFAULT_FORM_PATH)!!.trim(), DEFAULT_FORM_PATH),
            historyPath = normalizePath(prefs.getString(KEY_HISTORY_PATH, DEFAULT_HISTORY_PATH)!!.trim(), DEFAULT_HISTORY_PATH),
            postCallPromptTimeoutSeconds = prefs.getInt(KEY_POST_CALL_TIMEOUT, DEFAULT_POST_CALL_TIMEOUT_SECONDS).coerceIn(3, 120),
            useCustomStartPopup = prefs.getBoolean(KEY_USE_CUSTOM_START_POPUP, true),
            useCustomEndPopup = prefs.getBoolean(KEY_USE_CUSTOM_END_POPUP, true),
            contactLinkMode = normalizeContactLinkMode(prefs.getString(KEY_CONTACT_LINK_MODE, DEFAULT_CONTACT_LINK_MODE).orEmpty()),
        )
    }

    fun save(context: Context, config: AppConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REMOTE_ENABLED, config.remoteEnabled)
            .putString(KEY_BASE_URL, config.baseUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, config.accessToken.trim())
            .putString(KEY_CONTACT_GROUPS, config.contactGroups.trim())
            .putBoolean(KEY_NOTIFY_UNKNOWN_CONTACTS, config.notifyUnknownContacts)
            .putBoolean(KEY_NOTIFY_KNOWN_CONTACTS, config.notifyKnownContacts)
            .putString(KEY_LOOKUP_PATH, normalizePath(config.lookupPath, DEFAULT_LOOKUP_PATH))
            .putString(KEY_FORM_PATH, normalizePath(config.formPath, DEFAULT_FORM_PATH))
            .putString(KEY_HISTORY_PATH, normalizePath(config.historyPath, DEFAULT_HISTORY_PATH))
            .putInt(KEY_POST_CALL_TIMEOUT, config.postCallPromptTimeoutSeconds.coerceIn(3, 120))
            .putBoolean(KEY_USE_CUSTOM_START_POPUP, config.useCustomStartPopup)
            .putBoolean(KEY_USE_CUSTOM_END_POPUP, config.useCustomEndPopup)
            .putString(KEY_CONTACT_LINK_MODE, normalizeContactLinkMode(config.contactLinkMode))
            .apply()
    }

    private fun normalizePath(path: String, defaultPath: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return defaultPath
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }

    private fun normalizeContactLinkMode(value: String): String {
        return when (value.trim()) {
            CONTACT_LINK_MODE_CONTACT -> CONTACT_LINK_MODE_CONTACT
            else -> CONTACT_LINK_MODE_APP
        }
    }
}

fun buildEndpoint(baseUrl: String, path: String, params: Map<String, String>): String {
    val base = baseUrl.trim().trimEnd('/')
    val normalizedPath = if (path.startsWith('/')) path else "/$path"
    val builder = Uri.parse(base + normalizedPath).buildUpon().clearQuery()
    params.forEach { (key, value) ->
        if (value.isNotBlank()) builder.appendQueryParameter(key, value)
    }
    return builder.build().toString()
}

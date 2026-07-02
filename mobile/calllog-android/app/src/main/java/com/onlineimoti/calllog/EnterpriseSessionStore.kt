package com.onlineimoti.calllog

import android.content.Context

/**
 * Stores the short-lived company session used only by the public Play package.
 * The internal APK deliberately keeps its existing local/manual server mode.
 */
internal object EnterpriseSessionStore {
    private const val PREFS = "relationship_manager_enterprise_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_DISCLOSURE_ACCEPTED = "call_log_disclosure_accepted"

    data class Session(
        val token: String,
        val expiresAtMs: Long,
        val accountName: String,
        val userName: String,
    )

    fun isRequired(): Boolean = BuildConfig.IS_PLAY_DISTRIBUTION

    fun current(context: Context): Session? {
        if (!isRequired()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        val expiresAtMs = prefs.getLong(KEY_EXPIRES_AT_MS, 0L)
        if (token.isBlank() || !token.startsWith("rms1_") || expiresAtMs <= System.currentTimeMillis()) {
            if (token.isNotBlank() || expiresAtMs > 0L) clear(context)
            return null
        }
        return Session(
            token = token,
            expiresAtMs = expiresAtMs,
            accountName = prefs.getString(KEY_ACCOUNT_NAME, "").orEmpty().trim(),
            userName = prefs.getString(KEY_USER_NAME, "").orEmpty().trim(),
        )
    }

    fun hasActiveSession(context: Context): Boolean = !isRequired() || current(context) != null

    fun hasAcceptedCallLogDisclosure(context: Context): Boolean {
        if (!isRequired()) return true
        return current(context) != null && context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCLOSURE_ACCEPTED, false)
    }

    fun markCallLogDisclosureAccepted(context: Context) {
        if (!isRequired() || current(context) == null) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCLOSURE_ACCEPTED, true)
            .apply()
    }

    fun install(context: Context, response: EnterpriseLoginResponse) {
        require(isRequired()) { "Enterprise sessions are only valid for the Play package." }
        require(response.sessionToken.startsWith("rms1_")) { "Unexpected enterprise session token." }
        require(response.expiresAtMs > System.currentTimeMillis()) { "Expired enterprise session." }

        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, response.sessionToken)
            .putLong(KEY_EXPIRES_AT_MS, response.expiresAtMs)
            .putString(KEY_ACCOUNT_NAME, response.accountName)
            .putString(KEY_USER_NAME, response.userName)
            .putBoolean(KEY_DISCLOSURE_ACCEPTED, false)
            .apply()

        val currentConfig = ConfigStore.load(appContext)
        ConfigStore.save(
            appContext,
            currentConfig.copy(
                remoteEnabled = true,
                baseUrl = BuildConfig.ENTERPRISE_SERVER_BASE_URL,
                accessToken = response.sessionToken,
                lookupPath = response.lookupPath,
                formPath = response.formPath,
                historyPath = response.historyPath,
            ),
        )
    }

    fun clear(context: Context) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        if (!isRequired()) return

        val currentConfig = ConfigStore.load(appContext)
        ConfigStore.save(
            appContext,
            currentConfig.copy(
                remoteEnabled = false,
                baseUrl = "",
                accessToken = "",
                lookupPath = ConfigStore.DEFAULT_LOOKUP_PATH,
                formPath = ConfigStore.DEFAULT_FORM_PATH,
                historyPath = ConfigStore.DEFAULT_HISTORY_PATH,
            ),
        )
    }
}

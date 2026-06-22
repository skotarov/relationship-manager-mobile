package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment

data class AppConfig(
    val remoteEnabled: Boolean,
    val baseUrl: String,
    val accessToken: String,
    val contactGroups: String,
    val notifyUnknownContacts: Boolean,
    val notifyKnownContacts: Boolean,
    val homeCallPageSize: Int,
    val lookupPath: String,
    val formPath: String,
    val historyPath: String,
    val postCallPromptTimeoutSeconds: Int,
    val useOverlayPopups: Boolean,
    val useCustomStartPopup: Boolean,
    val useCustomEndPopup: Boolean,
    val postCallEndAction: String,
    val contactLinkMode: String,
    val showCrmActionButtons: Boolean,
    val showBulkContactSyncNotifications: Boolean,
    val appLanguage: String,
    val usePublicNotesFolder: Boolean,
    val useCallScreening: Boolean,
    val showRmDebugBox: Boolean,
    val useLocalNotesStorage: Boolean = true,
    val useFullScreenPopup: Boolean = true,
    val useInternalSmsComposer: Boolean = false,
)

object ConfigStore {
    private const val PREFS = "callreport_prefs"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_CONTACT_GROUPS = "contact_groups"
    private const val KEY_NOTIFY_UNKNOWN_CONTACTS = "notify_unknown_contacts"
    private const val KEY_NOTIFY_KNOWN_CONTACTS = "notify_known_contacts"
    private const val KEY_HOME_CALL_PAGE_SIZE = "home_call_page_size"
    private const val KEY_LOOKUP_PATH = "lookup_path"
    private const val KEY_FORM_PATH = "form_path"
    private const val KEY_HISTORY_PATH = "history_path"
    private const val KEY_POST_CALL_TIMEOUT = "post_call_timeout"
    private const val KEY_USE_OVERLAY_POPUPS = "use_overlay_popups"
    private const val KEY_USE_CUSTOM_START_POPUP = "use_custom_start_popup"
    private const val KEY_USE_CUSTOM_END_POPUP = "use_custom_end_popup"
    private const val KEY_POST_CALL_END_ACTION = "post_call_end_action"
    private const val KEY_CONTACT_LINK_MODE = "contact_link_mode"
    private const val KEY_SHOW_CRM_ACTION_BUTTONS = "show_crm_action_buttons"
    private const val KEY_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS = "show_bulk_contact_sync_notifications"
    private const val KEY_APP_LANGUAGE = "app_language"
    private const val KEY_USE_PUBLIC_NOTES_FOLDER = "use_public_notes_folder"
    private const val KEY_USE_CALL_SCREENING = "use_call_screening"
    private const val KEY_SHOW_RM_DEBUG_BOX = "show_rm_debug_box"
    private const val KEY_USE_LOCAL_NOTES_STORAGE = "use_local_notes_storage"
    private const val KEY_USE_FULL_SCREEN_POPUP = "use_full_screen_popup"
    private const val KEY_USE_INTERNAL_SMS_COMPOSER = "use_internal_sms_composer"

    const val DEFAULT_LOOKUP_PATH = "/broker/callreport/lookup.php"
    const val DEFAULT_FORM_PATH = "/broker/callreport/form.php"
    const val DEFAULT_HISTORY_PATH = "/broker/callreport/history.php"
    const val DEFAULT_POST_CALL_TIMEOUT_SECONDS = 10
    const val DEFAULT_HOME_CALL_PAGE_SIZE = 20
    const val MIN_HOME_CALL_PAGE_SIZE = 5
    const val MAX_HOME_CALL_PAGE_SIZE = 100
    const val POST_CALL_END_ACTION_EDIT = "edit"
    const val POST_CALL_END_ACTION_HISTORY = "history"
    const val POST_CALL_END_ACTION_NOTHING = "nothing"
    const val DEFAULT_POST_CALL_END_ACTION = POST_CALL_END_ACTION_EDIT
    const val CONTACT_LINK_MODE_APP = "app"
    const val CONTACT_LINK_MODE_CONTACT = "contact"
    const val DEFAULT_CONTACT_LINK_MODE = CONTACT_LINK_MODE_APP
    const val DEFAULT_SHOW_CRM_ACTION_BUTTONS = true
    const val DEFAULT_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS = false
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_BG = "bg"
    const val LANGUAGE_EN = "en"
    const val DEFAULT_APP_LANGUAGE = LANGUAGE_SYSTEM
    const val DEFAULT_USE_CALL_SCREENING = false
    const val DEFAULT_SHOW_RM_DEBUG_BOX = false
    const val DEFAULT_USE_LOCAL_NOTES_STORAGE = true
    const val DEFAULT_USE_FULL_SCREEN_POPUP = true
    const val DEFAULT_USE_INTERNAL_SMS_COMPOSER = false

    fun load(context: Context): AppConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppConfig(
            remoteEnabled = prefs.getBoolean(KEY_REMOTE_ENABLED, false),
            baseUrl = prefs.getString(KEY_BASE_URL, "https://onlineimoti.com")!!.trim(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, BuildConfig.DEFAULT_ACCESS_TOKEN)!!.trim(),
            contactGroups = prefs.getString(KEY_CONTACT_GROUPS, "")!!.trim(),
            notifyUnknownContacts = prefs.getBoolean(KEY_NOTIFY_UNKNOWN_CONTACTS, true),
            notifyKnownContacts = prefs.getBoolean(KEY_NOTIFY_KNOWN_CONTACTS, false),
            homeCallPageSize = prefs.getInt(KEY_HOME_CALL_PAGE_SIZE, DEFAULT_HOME_CALL_PAGE_SIZE).coerceHomeCallPageSize(),
            lookupPath = normalizePath(prefs.getString(KEY_LOOKUP_PATH, DEFAULT_LOOKUP_PATH)!!.trim(), DEFAULT_LOOKUP_PATH),
            formPath = normalizePath(prefs.getString(KEY_FORM_PATH, DEFAULT_FORM_PATH)!!.trim(), DEFAULT_FORM_PATH),
            historyPath = normalizePath(prefs.getString(KEY_HISTORY_PATH, DEFAULT_HISTORY_PATH)!!.trim(), DEFAULT_HISTORY_PATH),
            postCallPromptTimeoutSeconds = prefs.getInt(KEY_POST_CALL_TIMEOUT, DEFAULT_POST_CALL_TIMEOUT_SECONDS).coerceIn(3, 120),
            useOverlayPopups = prefs.getBoolean(KEY_USE_OVERLAY_POPUPS, false),
            useCustomStartPopup = prefs.getBoolean(KEY_USE_CUSTOM_START_POPUP, true),
            useCustomEndPopup = prefs.getBoolean(KEY_USE_CUSTOM_END_POPUP, true),
            postCallEndAction = normalizePostCallEndAction(prefs.getString(KEY_POST_CALL_END_ACTION, DEFAULT_POST_CALL_END_ACTION).orEmpty()),
            contactLinkMode = normalizeContactLinkMode(prefs.getString(KEY_CONTACT_LINK_MODE, DEFAULT_CONTACT_LINK_MODE).orEmpty()),
            showCrmActionButtons = prefs.getBoolean(KEY_SHOW_CRM_ACTION_BUTTONS, DEFAULT_SHOW_CRM_ACTION_BUTTONS),
            showBulkContactSyncNotifications = prefs.getBoolean(
                KEY_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS,
                DEFAULT_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS,
            ),
            appLanguage = normalizeAppLanguage(prefs.getString(KEY_APP_LANGUAGE, DEFAULT_APP_LANGUAGE).orEmpty()),
            usePublicNotesFolder = if (prefs.contains(KEY_USE_PUBLIC_NOTES_FOLDER)) {
                prefs.getBoolean(KEY_USE_PUBLIC_NOTES_FOLDER, false)
            } else {
                canUsePublicNotesFolderByDefault()
            },
            useCallScreening = prefs.getBoolean(KEY_USE_CALL_SCREENING, DEFAULT_USE_CALL_SCREENING),
            showRmDebugBox = prefs.getBoolean(KEY_SHOW_RM_DEBUG_BOX, DEFAULT_SHOW_RM_DEBUG_BOX),
            useLocalNotesStorage = prefs.getBoolean(KEY_USE_LOCAL_NOTES_STORAGE, DEFAULT_USE_LOCAL_NOTES_STORAGE),
            useFullScreenPopup = prefs.getBoolean(KEY_USE_FULL_SCREEN_POPUP, DEFAULT_USE_FULL_SCREEN_POPUP),
            useInternalSmsComposer = prefs.getBoolean(KEY_USE_INTERNAL_SMS_COMPOSER, DEFAULT_USE_INTERNAL_SMS_COMPOSER),
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
            .putInt(KEY_HOME_CALL_PAGE_SIZE, config.homeCallPageSize.coerceHomeCallPageSize())
            .putString(KEY_LOOKUP_PATH, normalizePath(config.lookupPath, DEFAULT_LOOKUP_PATH))
            .putString(KEY_FORM_PATH, normalizePath(config.formPath, DEFAULT_FORM_PATH))
            .putString(KEY_HISTORY_PATH, normalizePath(config.historyPath, DEFAULT_HISTORY_PATH))
            .putInt(KEY_POST_CALL_TIMEOUT, config.postCallPromptTimeoutSeconds.coerceIn(3, 120))
            .putBoolean(KEY_USE_OVERLAY_POPUPS, config.useOverlayPopups)
            .putBoolean(KEY_USE_CUSTOM_START_POPUP, config.useCustomStartPopup)
            .putBoolean(KEY_USE_CUSTOM_END_POPUP, config.useCustomEndPopup)
            .putString(KEY_POST_CALL_END_ACTION, normalizePostCallEndAction(config.postCallEndAction))
            .putString(KEY_CONTACT_LINK_MODE, normalizeContactLinkMode(config.contactLinkMode))
            .putBoolean(KEY_SHOW_CRM_ACTION_BUTTONS, config.showCrmActionButtons)
            .putBoolean(KEY_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS, config.showBulkContactSyncNotifications)
            .putString(KEY_APP_LANGUAGE, normalizeAppLanguage(config.appLanguage))
            .putBoolean(KEY_USE_PUBLIC_NOTES_FOLDER, config.usePublicNotesFolder)
            .putBoolean(KEY_USE_CALL_SCREENING, config.useCallScreening)
            .putBoolean(KEY_SHOW_RM_DEBUG_BOX, config.showRmDebugBox)
            .putBoolean(KEY_USE_LOCAL_NOTES_STORAGE, config.useLocalNotesStorage)
            .putBoolean(KEY_USE_FULL_SCREEN_POPUP, config.useFullScreenPopup)
            .putBoolean(KEY_USE_INTERNAL_SMS_COMPOSER, config.useInternalSmsComposer)
            .apply()
    }

    fun localeTagForLanguage(language: String): String {
        return when (normalizeAppLanguage(language)) {
            LANGUAGE_BG -> "bg"
            LANGUAGE_EN -> "en"
            else -> ""
        }
    }

    private fun Int.coerceHomeCallPageSize(): Int = coerceIn(MIN_HOME_CALL_PAGE_SIZE, MAX_HOME_CALL_PAGE_SIZE)

    private fun normalizePath(path: String, defaultPath: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return defaultPath
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }

    private fun normalizePostCallEndAction(value: String): String {
        return when (value.trim()) {
            POST_CALL_END_ACTION_HISTORY -> POST_CALL_END_ACTION_HISTORY
            POST_CALL_END_ACTION_NOTHING -> POST_CALL_END_ACTION_NOTHING
            else -> POST_CALL_END_ACTION_EDIT
        }
    }

    private fun normalizeContactLinkMode(value: String): String {
        return when (value.trim()) {
            CONTACT_LINK_MODE_CONTACT -> CONTACT_LINK_MODE_CONTACT
            else -> CONTACT_LINK_MODE_APP
        }
    }

    private fun normalizeAppLanguage(value: String): String {
        return when (value.trim()) {
            LANGUAGE_BG -> LANGUAGE_BG
            LANGUAGE_EN -> LANGUAGE_EN
            else -> LANGUAGE_SYSTEM
        }
    }

    private fun canUsePublicNotesFolderByDefault(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
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

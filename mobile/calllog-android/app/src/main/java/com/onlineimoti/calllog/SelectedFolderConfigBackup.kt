package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject

/**
 * Mirrors the app configuration into the user-selected public folder.
 *
 * Android cannot keep SAF permissions across uninstall, so the URI itself still
 * lives in SharedPreferences. After the user selects the same folder again, the
 * server settings and related app settings are restored from settings.json.
 */
internal object SelectedFolderConfigBackup {
    private const val SETTINGS_FILE = "settings.json"
    private const val LEGACY_ROOT_DIR = ".callreport"
    private const val NOTES_DIR = "notes"

    fun load(context: Context, uriString: String): AppConfig? {
        val file = configFile(context, uriString, create = false) ?: return null
        val text = context.contentResolver.openInputStream(file.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (text.isBlank()) return null
        return runCatching { parse(JSONObject(text)) }.getOrNull()
    }

    fun save(context: Context, config: AppConfig) {
        val uriString = config.localNotesFolderUri.trim()
        if (uriString.isBlank()) return
        val file = configFile(context, uriString, create = true) ?: return
        val json = toJson(config).toString(2)
        runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")
                ?.bufferedWriter()
                ?.use { it.write(json) }
        }
    }

    private fun configFile(context: Context, uriString: String, create: Boolean): DocumentFile? {
        val uri = runCatching { Uri.parse(uriString.trim()) }.getOrNull() ?: return null
        if (!hasAccess(context, uri)) return null
        val tree = DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() } ?: return null
        rootCandidates(tree).forEach { root ->
            root.listFiles().firstOrNull { it.isFile && it.name == SETTINGS_FILE }?.let { return it }
        }
        if (!create) return null
        val root = rootForWrite(tree) ?: return null
        return root.createFile("application/json", SETTINGS_FILE)
    }

    private fun hasAccess(context: Context, uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri &&
                permission.isReadPermission &&
                permission.isWritePermission &&
                (permission.persistedTime >= 0L || flags != 0)
        }
    }

    private fun rootCandidates(tree: DocumentFile): List<DocumentFile> {
        val result = arrayListOf(tree)
        if (tree.name != LEGACY_ROOT_DIR && tree.name != NOTES_DIR) {
            tree.listFiles().firstOrNull { it.isDirectory && it.name == LEGACY_ROOT_DIR }?.let { result += it }
        }
        return result.distinctBy { it.uri }
    }

    private fun rootForWrite(tree: DocumentFile): DocumentFile? {
        // The selected folder is the workspace. Do not hide new settings under
        // another .callreport folder; legacy .callreport is read only as fallback.
        return tree.takeIf { it.canWrite() }
    }

    private fun parse(json: JSONObject): AppConfig = AppConfig(
        remoteEnabled = json.optBoolean("remote_enabled", false),
        baseUrl = json.optString("base_url"),
        accessToken = json.optString("access_token"),
        contactGroups = json.optString("contact_groups"),
        notifyUnknownContacts = json.optBoolean("notify_unknown_contacts", true),
        notifyKnownContacts = json.optBoolean("notify_known_contacts", false),
        homeCallPageSize = json.optInt("home_call_page_size", ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE),
        lookupPath = json.optString("lookup_path", ConfigStore.DEFAULT_LOOKUP_PATH),
        formPath = json.optString("form_path", ConfigStore.DEFAULT_FORM_PATH),
        historyPath = json.optString("history_path", ConfigStore.DEFAULT_HISTORY_PATH),
        postCallPromptTimeoutSeconds = json.optInt("post_call_timeout", ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS),
        useOverlayPopups = json.optBoolean("use_overlay_popups", false),
        useCustomStartPopup = json.optBoolean("use_custom_start_popup", true),
        useCustomEndPopup = json.optBoolean("use_custom_end_popup", true),
        postCallEndAction = json.optString("post_call_end_action", ConfigStore.DEFAULT_POST_CALL_END_ACTION),
        contactLinkMode = json.optString("contact_link_mode", ConfigStore.DEFAULT_CONTACT_LINK_MODE),
        showCrmActionButtons = json.optBoolean("show_crm_action_buttons", ConfigStore.DEFAULT_SHOW_CRM_ACTION_BUTTONS),
        showBulkContactSyncNotifications = json.optBoolean(
            "show_bulk_contact_sync_notifications",
            ConfigStore.DEFAULT_SHOW_BULK_CONTACT_SYNC_NOTIFICATIONS,
        ),
        appLanguage = json.optString("app_language", ConfigStore.DEFAULT_APP_LANGUAGE),
        usePublicNotesFolder = false,
        useCallScreening = json.optBoolean("use_call_screening", ConfigStore.DEFAULT_USE_CALL_SCREENING),
        showRmDebugBox = json.optBoolean("show_rm_debug_box", ConfigStore.DEFAULT_SHOW_RM_DEBUG_BOX),
        useLocalNotesStorage = json.optBoolean("use_local_notes_storage", ConfigStore.DEFAULT_USE_LOCAL_NOTES_STORAGE),
        localNotesFolderUri = "",
        useFullScreenPopup = false,
        useInternalSmsComposer = false,
    )

    private fun toJson(config: AppConfig): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("updated_at", System.currentTimeMillis())
        put("remote_enabled", config.remoteEnabled)
        put("base_url", config.baseUrl)
        put("access_token", config.accessToken)
        put("contact_groups", config.contactGroups)
        put("notify_unknown_contacts", config.notifyUnknownContacts)
        put("notify_known_contacts", config.notifyKnownContacts)
        put("home_call_page_size", config.homeCallPageSize)
        put("lookup_path", config.lookupPath)
        put("form_path", config.formPath)
        put("history_path", config.historyPath)
        put("post_call_timeout", config.postCallPromptTimeoutSeconds)
        put("use_overlay_popups", config.useOverlayPopups)
        put("use_custom_start_popup", config.useCustomStartPopup)
        put("use_custom_end_popup", config.useCustomEndPopup)
        put("post_call_end_action", config.postCallEndAction)
        put("contact_link_mode", config.contactLinkMode)
        put("show_crm_action_buttons", config.showCrmActionButtons)
        put("show_bulk_contact_sync_notifications", config.showBulkContactSyncNotifications)
        put("app_language", config.appLanguage)
        put("use_call_screening", config.useCallScreening)
        put("show_rm_debug_box", config.showRmDebugBox)
        put("use_local_notes_storage", config.useLocalNotesStorage)
    }
}

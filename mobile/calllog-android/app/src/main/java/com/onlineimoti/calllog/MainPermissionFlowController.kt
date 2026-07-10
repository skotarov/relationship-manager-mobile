package com.onlineimoti.calllog

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat

internal class MainPermissionFlowController(
    private val activity: MainActivity,
    private val requestPermissionLauncher: ActivityResultLauncher<String>,
    private val callScreeningRoleLauncher: ActivityResultLauncher<Intent>,
    private val storageSettingsLauncher: ActivityResultLauncher<Intent>,
    private val overlaySettingsLauncher: ActivityResultLauncher<Intent>,
    private val requestDefaultSmsRole: () -> Unit,
    private val requestSmsPermissions: () -> Unit,
    private val isDefaultSmsApp: () -> Boolean,
    private val hasSmsPermissions: () -> Boolean,
    private val hasPermission: (String) -> Boolean,
    private val disableOverlayPopups: () -> Unit,
    @Suppress("UNUSED_PARAMETER") private val disableCallScreening: () -> Unit,
    private val refreshPermissionSummary: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    private companion object {
        const val PERMISSION_REQUESTS_PREFS = "relationship_manager_permission_requests"
    }

    private val permissionRequests by lazy {
        activity.getSharedPreferences(PERMISSION_REQUESTS_PREFS, Context.MODE_PRIVATE)
    }

    private var isRunning = false
    private var lastRequestedRuntimePermission: String = ""
    private var lastRequestedRuntimePermissionLabel: String = ""

    fun start() {
        if (isRunning) return
        if (DistributionCapabilities.isPlayBusinessBuild) {
            isRunning = false
            setStatus(activity.getString(R.string.runtime_play_corporate_crm_ready))
            refreshPermissionSummary()
            return
        }
        isRunning = true
        requestNextStep()
    }

    /**
     * Shows Android's permission dialog on the first request and after a normal
     * denial. App settings are opened only after a prior request was denied with
     * "Don't ask again", when Android cannot show the dialog any more.
     */
    fun requestAppPermissionOrOpenSettings(permission: String, label: String) {
        isRunning = false
        if (DistributionCapabilities.isPlayBusinessBuild && isCorporateTelephonyPermission(permission)) {
            setStatus(activity.getString(R.string.runtime_play_local_feature_unavailable))
            refreshPermissionSummary()
            return
        }
        val localizedLabel = permissionLabel(permission, label)
        if (hasPermission(permission)) {
            setStatus(activity.getString(R.string.permission_flow_already_enabled, localizedLabel))
            refreshPermissionSummary()
            return
        }
        if (canShowPermissionDialog(permission)) {
            requestRuntimePermission(
                permission,
                activity.getString(R.string.permission_flow_request_from_dialog, localizedLabel),
                localizedLabel,
            )
            return
        }
        setStatus(activity.getString(R.string.permission_flow_enable_in_settings, localizedLabel))
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
        )
    }

    /** Shared Documents is optional. Without it LocalNotesFileStore falls back to private app storage. */
    fun requestSharedNotesStoragePermission() {
        isRunning = false
        if (LocalNotesFileStore.canUsePublicFolder()) {
            onStorageSettingsResult()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setStatus("Разреши достъп до общото хранилище. Тогава локалните бележки ще се четат и записват в Documents/.callreport.")
            storageSettingsLauncher.launch(sharedStorageSettingsIntent())
        } else {
            requestRuntimePermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                "Разреши достъп до общото хранилище за локалните бележки.",
                "Общо хранилище",
            )
        }
    }

    fun openSharedNotesStorageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageSettingsLauncher.launch(sharedStorageSettingsIntent())
        } else {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
        }
    }

    fun onPermissionResult() {
        val completedPermission = lastRequestedRuntimePermission
        val deniedPermission = completedPermission.takeIf { it.isNotBlank() && !hasPermission(it) }
        val deniedLabel = lastRequestedRuntimePermissionLabel
        lastRequestedRuntimePermission = ""
        lastRequestedRuntimePermissionLabel = ""
        refreshPermissionSummary()
        if (completedPermission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            onStorageSettingsResult()
            return
        }
        if (deniedPermission != null) {
            isRunning = false
            setStatus(activity.getString(R.string.permission_flow_permission_not_enabled, deniedLabel))
            return
        }
        requestNextStep()
    }

    /** Called after Android's Default SMS app role chooser closes. */
    fun onSmsRoleResult() {
        refreshPermissionSummary()
        if (!isDefaultSmsApp()) {
            isRunning = false
            setStatus(activity.getString(R.string.settings_sms_role_not_changed))
            return
        }
        if (hasSmsPermissions()) {
            continueAfterSmsSetup()
            return
        }
        setStatus(activity.getString(R.string.permission_flow_request_from_dialog, "SMS"))
        requestSmsPermissions()
    }

    /** Called after the Android runtime SMS permissions dialog closes. */
    fun onSmsPermissionsResult() {
        refreshPermissionSummary()
        if (!hasSmsPermissions()) {
            isRunning = false
            setStatus(activity.getString(R.string.permission_flow_permission_not_enabled, "SMS"))
            return
        }
        continueAfterSmsSetup()
    }

    fun onCallScreeningResult() {
        if (hasCallScreeningRole()) {
            setStatus(activity.getString(R.string.permission_flow_screening_active))
        } else {
            setStatus(activity.getString(R.string.permission_flow_screening_not_active))
        }
        isRunning = false
        refreshPermissionSummary()
    }

    fun onStorageSettingsResult() {
        val sharedActive = LocalNotesFileStore.canUsePublicFolder()
        if (sharedActive) {
            LocalNotesFileStore.migratePrivateToPublic(activity)
            setStatus("Локалните бележки се четат и записват в Documents/.callreport.")
        } else {
            setStatus("Липсва достъп до общото хранилище. Локалните бележки остават в личната папка на приложението.")
        }
        refreshPermissionSummary()
        isRunning = false
    }

    fun onOverlaySettingsResult() {
        if (Settings.canDrawOverlays(activity)) {
            setStatus(activity.getString(R.string.permission_flow_overlay_allowed))
        } else if (overlayPopupsSelected()) {
            disableOverlayPopups()
            setStatus(activity.getString(R.string.permission_flow_overlay_denied))
        }
        refreshPermissionSummary()
        isRunning = false
    }

    fun requestNextStep() {
        if (DistributionCapabilities.isPlayBusinessBuild) {
            isRunning = false
            setStatus(activity.getString(R.string.runtime_play_corporate_crm_ready))
            refreshPermissionSummary()
            return
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) -> {
                requestRuntimePermission(
                    Manifest.permission.POST_NOTIFICATIONS,
                    activity.getString(R.string.permission_flow_request_notifications),
                    activity.getString(R.string.permission_label_notifications),
                )
            }
            !hasPermission(Manifest.permission.READ_PHONE_STATE) -> {
                requestRuntimePermission(
                    Manifest.permission.READ_PHONE_STATE,
                    activity.getString(R.string.permission_flow_request_phone),
                    activity.getString(R.string.permission_label_phone),
                )
            }
            !hasPermission(Manifest.permission.READ_CALL_LOG) -> {
                requestRuntimePermission(
                    Manifest.permission.READ_CALL_LOG,
                    activity.getString(R.string.permission_flow_request_call_log),
                    activity.getString(R.string.permission_label_call_log),
                )
            }
            !hasPermission(Manifest.permission.READ_CONTACTS) -> {
                requestRuntimePermission(
                    Manifest.permission.READ_CONTACTS,
                    activity.getString(
                        R.string.permission_flow_request_from_dialog,
                        activity.getString(R.string.permission_label_contacts_read),
                    ),
                    activity.getString(R.string.permission_label_contacts_read),
                )
            }
            BuildConfig.DEBUG && !smsSetupIsComplete() -> requestSmsSetup()
            else -> finishFlowWithSuccess()
        }
    }

    fun requestCallScreeningRoleIfNeeded() {
        if (DistributionCapabilities.isPlayBusinessBuild) {
            setStatus(activity.getString(R.string.runtime_play_local_feature_unavailable))
            refreshPermissionSummary()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasCallScreeningRole()) {
            finishFlowWithoutStatus()
            return
        }
        val roleManager = activity.getSystemService(RoleManager::class.java) ?: run {
            reportUnavailableCallScreening()
            return
        }
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            reportUnavailableCallScreening()
            return
        }
        callScreeningRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    fun requestOverlayPermissionIfNeeded() {
        if (DistributionCapabilities.isPlayBusinessBuild) {
            setStatus(activity.getString(R.string.runtime_play_local_feature_unavailable))
            refreshPermissionSummary()
            return
        }
        if (Settings.canDrawOverlays(activity)) {
            setStatus(activity.getString(R.string.permission_flow_overlay_already_allowed))
            refreshPermissionSummary()
            return
        }
        overlaySettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
        )
    }

    private fun requestSmsSetup() {
        if (!isDefaultSmsApp()) {
            setStatus("Избери Relationship Manager като SMS приложение. След това Android ще поиска SMS разрешенията с обикновен диалог.")
            requestDefaultSmsRole()
            return
        }
        if (!hasSmsPermissions()) {
            setStatus(activity.getString(R.string.permission_flow_request_from_dialog, "SMS"))
            requestSmsPermissions()
            return
        }
        continueAfterSmsSetup()
    }

    private fun continueAfterSmsSetup() {
        if (isRunning) {
            requestNextStep()
        } else {
            setStatus(activity.getString(R.string.settings_sms_role_active))
        }
    }

    private fun smsSetupIsComplete(): Boolean = isDefaultSmsApp() && hasSmsPermissions()

    private fun requestRuntimePermission(permission: String, status: String, label: String) {
        permissionRequests.edit().putBoolean(permission, true).apply()
        lastRequestedRuntimePermission = permission
        lastRequestedRuntimePermissionLabel = label
        setStatus(status)
        requestPermissionLauncher.launch(permission)
    }

    private fun canShowPermissionDialog(permission: String): Boolean {
        return !permissionRequests.getBoolean(permission, false) ||
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun sharedStorageSettingsIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
    }

    private fun reportUnavailableCallScreening() {
        setStatus(activity.getString(R.string.permission_flow_screening_unavailable))
        isRunning = false
        refreshPermissionSummary()
    }

    private fun permissionLabel(permission: String, fallback: String): String = when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> activity.getString(R.string.permission_label_notifications)
        Manifest.permission.READ_PHONE_STATE -> activity.getString(R.string.permission_label_phone)
        Manifest.permission.READ_CALL_LOG -> activity.getString(R.string.permission_label_call_log)
        Manifest.permission.READ_CONTACTS -> activity.getString(R.string.permission_label_contacts_read)
        Manifest.permission.WRITE_CONTACTS -> activity.getString(R.string.permission_label_contacts_write)
        else -> fallback
    }

    private fun isCorporateTelephonyPermission(permission: String): Boolean {
        return permission == Manifest.permission.READ_PHONE_STATE || permission == Manifest.permission.READ_CALL_LOG
    }

    private fun overlayPopupsSelected(): Boolean = ConfigStore.load(activity).useOverlayPopups

    private fun hasCallScreeningRole(): Boolean = MainPermissionChecks.hasCallScreeningRole(activity)

    private fun finishFlowWithSuccess() {
        isRunning = false
        setStatus(activity.getString(R.string.permission_flow_success, LocalNotesFileStore.activeRootPath(activity)))
        refreshPermissionSummary()
    }

    private fun finishFlowWithoutStatus() {
        isRunning = false
        refreshPermissionSummary()
    }
}

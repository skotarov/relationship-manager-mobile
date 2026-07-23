package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

/** Handles permission flows that leave the app for an Android Settings screen. */
internal class MainPermissionSettingsNavigator(
    private val activity: MainActivity,
    private val storageSettingsLauncher: ActivityResultLauncher<Intent>,
    private val overlaySettingsLauncher: ActivityResultLauncher<Intent>,
    private val disableOverlayPopups: () -> Unit,
    private val refreshPermissionSummary: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    fun requestAppPermissionOrOpenSettings(
        permission: String,
        fallbackLabel: String,
        hasPermission: (String) -> Boolean,
        canShowPermissionDialog: (String) -> Boolean,
        requestRuntimePermission: (String, String, String) -> Unit,
    ) {
        if (DistributionCapabilities.isPlayBusinessBuild && isCorporateTelephonyPermission(permission)) {
            setStatus(activity.getString(R.string.runtime_play_local_feature_unavailable))
            refreshPermissionSummary()
            return
        }
        val label = permissionLabel(permission, fallbackLabel)
        if (hasPermission(permission)) {
            setStatus(activity.getString(R.string.permission_flow_already_enabled, label))
            refreshPermissionSummary()
            return
        }
        if (canShowPermissionDialog(permission)) {
            requestRuntimePermission(
                permission,
                activity.getString(R.string.permission_flow_request_from_dialog, label),
                label,
            )
            return
        }
        setStatus(activity.getString(R.string.permission_flow_enable_in_settings, label))
        openApplicationDetails()
    }

    fun requestSharedNotesStoragePermission(
        requestRuntimePermission: (String, String, String) -> Unit,
        onAlreadyAvailable: () -> Unit,
    ) {
        if (LocalNotesFileStore.canUsePublicFolder()) {
            onAlreadyAvailable()
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
            openApplicationDetails()
        }
    }

    fun reportStorageSettingsResult() {
        if (LocalNotesFileStore.canUsePublicFolder()) {
            LocalNotesFileStore.migratePrivateToPublic(activity)
            setStatus("Локалните бележки се четат и записват в Documents/.callreport.")
        } else {
            setStatus("Липсва достъп до общото хранилище. Локалните бележки остават в личната папка на приложението.")
        }
        refreshPermissionSummary()
    }

    fun reportOverlaySettingsResult() {
        if (Settings.canDrawOverlays(activity)) {
            setStatus(activity.getString(R.string.permission_flow_overlay_allowed))
        } else if (ConfigStore.load(activity).useOverlayPopups) {
            disableOverlayPopups()
            setStatus(activity.getString(R.string.permission_flow_overlay_denied))
        }
        refreshPermissionSummary()
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

    private fun openApplicationDetails() {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
        )
    }

    private fun sharedStorageSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${activity.packageName}")
        }

    private fun permissionLabel(permission: String, fallback: String): String = when (permission) {
        Manifest.permission.POST_NOTIFICATIONS -> activity.getString(R.string.permission_label_notifications)
        Manifest.permission.READ_PHONE_STATE -> activity.getString(R.string.permission_label_phone)
        Manifest.permission.READ_CALL_LOG -> activity.getString(R.string.permission_label_call_log)
        Manifest.permission.READ_CONTACTS -> activity.getString(R.string.permission_label_contacts_read)
        Manifest.permission.WRITE_CONTACTS -> activity.getString(R.string.permission_label_contacts_write)
        else -> fallback
    }

    private fun isCorporateTelephonyPermission(permission: String): Boolean =
        permission == Manifest.permission.READ_PHONE_STATE || permission == Manifest.permission.READ_CALL_LOG
}

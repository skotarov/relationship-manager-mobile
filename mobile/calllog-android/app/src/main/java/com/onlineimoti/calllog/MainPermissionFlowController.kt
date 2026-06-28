package com.onlineimoti.calllog

import android.Manifest
import android.app.role.RoleManager
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
    private val overlaySettingsLauncher: ActivityResultLauncher<Intent>,
    private val hasPermission: (String) -> Boolean,
    private val disableOverlayPopups: () -> Unit,
    @Suppress("UNUSED_PARAMETER") private val disableCallScreening: () -> Unit,
    private val refreshPermissionSummary: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    private var isRunning = false
    private var lastRequestedRuntimePermission: String = ""
    private var lastRequestedRuntimePermissionLabel: String = ""

    fun start() {
        if (isRunning) return
        isRunning = true
        requestNextStep()
    }

    fun requestAppPermissionOrOpenSettings(permission: String, label: String) {
        isRunning = false
        val localizedLabel = permissionLabel(permission, label)
        if (hasPermission(permission)) {
            setStatus(activity.getString(R.string.permission_flow_already_enabled, localizedLabel))
            refreshPermissionSummary()
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
            requestRuntimePermission(
                permission,
                activity.getString(R.string.permission_flow_request_from_dialog, localizedLabel),
                localizedLabel,
            )
        } else {
            setStatus(activity.getString(R.string.permission_flow_enable_in_settings, localizedLabel))
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
            )
        }
    }

    fun onPermissionResult() {
        val deniedPermission = lastRequestedRuntimePermission.takeIf { it.isNotBlank() && !hasPermission(it) }
        val deniedLabel = lastRequestedRuntimePermissionLabel
        lastRequestedRuntimePermission = ""
        lastRequestedRuntimePermissionLabel = ""
        refreshPermissionSummary()
        if (deniedPermission != null) {
            isRunning = false
            setStatus(activity.getString(R.string.permission_flow_permission_not_enabled, deniedLabel))
            return
        }
        requestNextStep()
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
            else -> finishFlowWithSuccess()
        }
    }

    fun requestCallScreeningRoleIfNeeded() {
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

    private fun requestRuntimePermission(permission: String, status: String, label: String) {
        lastRequestedRuntimePermission = permission
        lastRequestedRuntimePermissionLabel = label
        setStatus(status)
        requestPermissionLauncher.launch(permission)
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

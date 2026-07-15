package com.onlineimoti.calllog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainPermissionSummary {
    fun refresh(activity: MainActivity, binding: ActivityMainBinding) {
        val permissions = binding.permissionsSection
        val popup = binding.popupSettingsSection
        val config = ConfigStore.load(activity)
        val notificationsGranted = hasNotificationPermission(activity)
        val phoneGranted = hasPermission(activity, Manifest.permission.READ_PHONE_STATE)
        val callLogGranted = hasPermission(activity, Manifest.permission.READ_CALL_LOG)
        val contactsGranted = hasPermission(activity, Manifest.permission.READ_CONTACTS)
        val contactsWriteGranted = hasPermission(activity, Manifest.permission.WRITE_CONTACTS)
        val overlayGranted = Settings.canDrawOverlays(activity)
        val overlaySelected = config.useOverlayPopups || popup.useOverlayPopupsCheckBox.isChecked
        val callScreeningSelected = permissions.useCallScreeningCheckBox.isChecked
        val callScreeningGranted = MainPermissionChecks.hasCallScreeningRole(activity)

        val rows = buildList {
            add(
                MainPermissionRow(
                    label = "Notifications",
                    active = notificationsGranted,
                    onEnable = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            activity.requestAppPermissionFromSummary(Manifest.permission.POST_NOTIFICATIONS, "Notifications")
                        } else {
                            openNotificationSettings(activity)
                        }
                    },
                    onDisable = { openNotificationSettings(activity) },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Phone",
                    active = phoneGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_PHONE_STATE, "Phone") },
                    onDisable = { openAppPermissionsSettings(activity, "Phone") },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Call log",
                    active = callLogGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CALL_LOG, "Call log") },
                    onDisable = { openAppPermissionsSettings(activity, "Call log") },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Contacts read",
                    active = contactsGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CONTACTS, "Contacts read") },
                    onDisable = { openAppPermissionsSettings(activity, "Contacts read") },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Contacts write",
                    active = contactsWriteGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.WRITE_CONTACTS, "Contacts write") },
                    onDisable = { openAppPermissionsSettings(activity, "Contacts write") },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Private notes storage",
                    active = config.useLocalNotesStorage,
                    inactiveLabel = "изключено",
                    onEnable = { setLocalNotesStorage(activity, binding, true) },
                    onDisable = { setLocalNotesStorage(activity, binding, false) },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Display over other apps",
                    active = overlaySelected && overlayGranted,
                    inactiveLabel = if (overlaySelected) "липсва" else "изключено",
                    onEnable = { enableOverlayPopups(activity, binding) },
                    onDisable = { disableOverlayPopups(activity, binding) },
                ),
            )
            add(
                MainPermissionRow(
                    label = "Call screening",
                    active = callScreeningSelected && callScreeningGranted,
                    inactiveLabel = if (callScreeningSelected) "липсва" else "изключено",
                    onEnable = { enableCallScreening(activity, binding) },
                    onDisable = { disableCallScreening(activity, binding) },
                ),
            )
        }

        permissions.permissionsSummaryText.visibility = View.GONE
        MainPermissionStatusRowsUi.render(activity, binding, rows)

        val overlayMissing = overlaySelected && !overlayGranted
        popup.overlayPermissionWarningText.visibility = if (overlayMissing) View.VISIBLE else View.GONE
        popup.useCustomStartPopupCheckBox.isEnabled = !overlayMissing
        popup.useCustomEndPopupCheckBox.isEnabled = !overlayMissing
        popup.overlayPopupOptionsGroup.alpha = if (overlayMissing) 0.55f else 1f

        permissions.openAppPermissionsButton.visibility = View.GONE
        permissions.openOverlayPermissionButton.visibility = View.GONE
        permissions.openCallScreeningButton.visibility = View.GONE
        permissions.openSmsRoleButton.visibility = View.GONE
        permissions.openFullscreenIntentButton.visibility = View.GONE
    }

    private fun setLocalNotesStorage(activity: MainActivity, binding: ActivityMainBinding, enabled: Boolean) {
        saveCurrentConfig(activity, binding) { it.copy(useLocalNotesStorage = enabled) }
        refresh(activity, binding)
        showToast(
            activity,
            if (enabled) "Локалните бележки се пазят в частната памет на приложението."
            else "Локалните бележки са изключени.",
        )
    }

    private fun enableOverlayPopups(activity: MainActivity, binding: ActivityMainBinding) {
        binding.popupSettingsSection.useOverlayPopupsCheckBox.isChecked = true
        binding.settingsApplicationGroup.applicationUseOverlayPopupsCheckBox.isChecked = true
        binding.popupSettingsSection.overlayPopupOptionsGroup.visibility = View.VISIBLE
        saveCurrentConfig(activity, binding) { it.copy(useOverlayPopups = true) }
        activity.requestOverlayPermissionFromSummary()
    }

    private fun disableOverlayPopups(activity: MainActivity, binding: ActivityMainBinding) {
        binding.popupSettingsSection.useOverlayPopupsCheckBox.isChecked = false
        binding.settingsApplicationGroup.applicationUseOverlayPopupsCheckBox.isChecked = false
        binding.popupSettingsSection.overlayPopupOptionsGroup.visibility = View.GONE
        saveCurrentConfig(activity, binding) { it.copy(useOverlayPopups = false) }
        refresh(activity, binding)
        openOverlaySettings(activity)
    }

    private fun enableCallScreening(activity: MainActivity, binding: ActivityMainBinding) {
        binding.permissionsSection.useCallScreeningCheckBox.isChecked = true
        saveCurrentConfig(activity, binding) { it.copy(useCallScreening = true) }
        activity.requestCallScreeningPermissionFromSummary()
    }

    private fun disableCallScreening(activity: MainActivity, binding: ActivityMainBinding) {
        binding.permissionsSection.useCallScreeningCheckBox.isChecked = false
        saveCurrentConfig(activity, binding) { it.copy(useCallScreening = false) }
        refresh(activity, binding)
        openDefaultAppsSettings(
            activity,
            "Call screening е изключено в приложението. Избери друго Caller ID / Spam приложение, ако искаш да премахнеш и системната роля.",
        )
    }

    private fun saveCurrentConfig(
        activity: MainActivity,
        binding: ActivityMainBinding,
        update: (AppConfig) -> AppConfig,
    ) {
        ConfigStore.save(activity, update(MainSettingsConfigUi.read(binding)))
    }

    private fun openNotificationSettings(activity: MainActivity) {
        openSettings(
            activity,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            },
            "Изключи известията от Android екрана за известия на приложението.",
        )
    }

    private fun openAppPermissionsSettings(activity: MainActivity, label: String) {
        openSettings(
            activity,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            "Изключи $label от Android: Разрешения на приложението.",
        )
    }

    private fun openOverlaySettings(activity: MainActivity) {
        openSettings(
            activity,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            "Overlay popup-ите са изключени. От Android изключи и „Display over other apps“, за да отнемеш самото системно разрешение.",
        )
    }

    private fun openDefaultAppsSettings(activity: MainActivity, message: String) {
        openSettings(activity, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), message)
    }

    private fun openSettings(activity: MainActivity, intent: Intent, message: String) {
        runCatching { activity.startActivity(intent) }
            .onSuccess { showToast(activity, message) }
            .onFailure { showToast(activity, "Не успях да отворя Android настройките: ${it.message.orEmpty()}") }
    }

    private fun showToast(activity: MainActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun hasNotificationPermission(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(activity: MainActivity, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }
}

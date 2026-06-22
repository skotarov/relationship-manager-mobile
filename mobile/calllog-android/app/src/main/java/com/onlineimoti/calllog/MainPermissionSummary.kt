package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainPermissionSummary {
    private const val STATUS_ROWS_TAG = "callreport_permission_status_rows"

    fun refresh(activity: MainActivity, binding: ActivityMainBinding) {
        val permissions = binding.permissionsSection
        val popup = binding.popupSettingsSection
        val storage = binding.storageSettingsSection
        val config = ConfigStore.load(activity)
        val notificationsGranted = hasNotificationPermission(activity)
        val phoneGranted = hasPermission(activity, Manifest.permission.READ_PHONE_STATE)
        val callLogGranted = hasPermission(activity, Manifest.permission.READ_CALL_LOG)
        val contactsGranted = hasPermission(activity, Manifest.permission.READ_CONTACTS)
        val contactsWriteGranted = hasPermission(activity, Manifest.permission.WRITE_CONTACTS)
        val smsDefault = SmsRoleController.isDefaultSmsApp(activity)
        val smsReceiveGranted = hasPermission(activity, Manifest.permission.RECEIVE_SMS)
        val smsReadGranted = hasPermission(activity, Manifest.permission.READ_SMS)
        val smsSendGranted = hasPermission(activity, Manifest.permission.SEND_SMS)
        val publicNotesSelected = storage.usePublicNotesFolderCheckBox.isChecked
        val publicNotesGranted = !publicNotesSelected || LocalNotesFileStore.canUsePublicFolder()
        val overlayGranted = Settings.canDrawOverlays(activity)
        val overlaySelected = config.useOverlayPopups || popup.useOverlayPopupsCheckBox.isChecked
        val callScreeningSelected = permissions.useCallScreeningCheckBox.isChecked
        val callScreeningGranted = MainPermissionChecks.hasCallScreeningRole(activity)
        val fullscreenGranted = canUseFullScreenIntent(activity)

        val rows = buildList {
            add(
                PermissionRow(
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
                )
            )
            add(
                PermissionRow(
                    label = "Phone",
                    active = phoneGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_PHONE_STATE, "Phone") },
                    onDisable = { openAppPermissionsSettings(activity, "Phone") },
                )
            )
            add(
                PermissionRow(
                    label = "Call report log",
                    active = callLogGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CALL_LOG, "Call log") },
                    onDisable = { openAppPermissionsSettings(activity, "Call log") },
                )
            )
            add(
                PermissionRow(
                    label = "Contacts read",
                    active = contactsGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CONTACTS, "Contacts read") },
                    onDisable = { openAppPermissionsSettings(activity, "Contacts read") },
                )
            )
            add(
                PermissionRow(
                    label = "Contacts write",
                    active = contactsWriteGranted,
                    onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.WRITE_CONTACTS, "Contacts write") },
                    onDisable = { openAppPermissionsSettings(activity, "Contacts write") },
                )
            )
            add(
                PermissionRow(
                    label = if (publicNotesSelected) "Public notes folder" else "Private notes storage",
                    active = publicNotesGranted,
                    onEnable = { activity.requestPublicNotesStoragePermissionFromSummary() },
                    onDisable = if (publicNotesSelected) {
                        { disablePublicNotesStorage(activity, binding) }
                    } else {
                        null
                    },
                )
            )
            add(
                PermissionRow(
                    label = "Display over other apps",
                    active = overlaySelected && overlayGranted,
                    inactiveLabel = if (overlaySelected) "липсва" else "изключено",
                    onEnable = { enableOverlayPopups(activity, binding) },
                    onDisable = { disableOverlayPopups(activity, binding) },
                )
            )
            add(
                PermissionRow(
                    label = "Call screening",
                    active = callScreeningSelected && callScreeningGranted,
                    inactiveLabel = if (callScreeningSelected) "липсва" else "изключено",
                    onEnable = { enableCallScreening(activity, binding) },
                    onDisable = { disableCallScreening(activity, binding) },
                )
            )
            add(
                PermissionRow(
                    label = "Default SMS",
                    active = smsDefault,
                    onEnable = { activity.requestDefaultSmsRoleFromSummary() },
                    onDisable = { openDefaultAppsSettings(activity, "Избери друго SMS приложение, за да изключиш Call Report като Default SMS.") },
                )
            )
            if (smsDefault) {
                add(
                    PermissionRow(
                        label = "SMS receive",
                        active = smsReceiveGranted,
                        onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.RECEIVE_SMS, "SMS receive") },
                        onDisable = { openAppPermissionsSettings(activity, "SMS receive") },
                    )
                )
                add(
                    PermissionRow(
                        label = "SMS read",
                        active = smsReadGranted,
                        onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.READ_SMS, "SMS read") },
                        onDisable = { openAppPermissionsSettings(activity, "SMS read") },
                    )
                )
                add(
                    PermissionRow(
                        label = "SMS send",
                        active = smsSendGranted,
                        onEnable = { activity.requestAppPermissionFromSummary(Manifest.permission.SEND_SMS, "SMS send") },
                        onDisable = { openAppPermissionsSettings(activity, "SMS send") },
                    )
                )
            }
            add(
                PermissionRow(
                    label = "Full-screen popup",
                    active = fullscreenGranted,
                    onEnable = { activity.requestFullScreenIntentPermissionFromSummary() },
                    onDisable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        { openFullScreenIntentSettings(activity) }
                    } else {
                        null
                    },
                )
            )
        }

        permissions.permissionsSummaryText.visibility = View.GONE
        renderStatusRows(activity, binding, rows)

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

    private fun renderStatusRows(activity: MainActivity, binding: ActivityMainBinding, rows: List<PermissionRow>) {
        val permissions = binding.permissionsSection
        val parent = permissions.permissionsSummaryText.parent as? LinearLayout ?: return
        parent.findViewWithTag<View>(STATUS_ROWS_TAG)?.let { parent.removeView(it) }

        val container = LinearLayout(activity).apply {
            tag = STATUS_ROWS_TAG
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(activity, 8) }
        }

        rows.forEach { row ->
            container.addView(permissionRowView(activity, row))
        }

        val insertIndex = parent.indexOfChild(permissions.permissionsSummaryText).coerceAtLeast(0) + 1
        parent.addView(container, insertIndex)
    }

    private fun permissionRowView(activity: MainActivity, row: PermissionRow): View {
        val activeColor = Color.rgb(20, 83, 45)
        val activeBg = Color.rgb(220, 252, 231)
        val activeBorder = Color.rgb(134, 239, 172)
        val missingColor = ContextCompat.getColor(activity, R.color.calllog_error)
        val missingBg = Color.rgb(254, 242, 242)
        val missingBorder = Color.rgb(252, 165, 165)

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
            background = roundedRect(
                if (row.active) activeBg else missingBg,
                dp(activity, 12),
                if (row.active) activeBorder else missingBorder,
                dp(activity, 1),
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(activity, 6) }

            addView(
                TextView(activity).apply {
                    text = "${row.label}: ${permissionStateLabel(row)}"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (row.active) activeColor else missingColor)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                }
            )

            when {
                !row.active -> addView(actionButton(activity, "Включи", row.onEnable))
                row.onDisable != null -> addView(actionButton(activity, "Изключи", row.onDisable))
            }
        }
    }

    private fun actionButton(activity: MainActivity, text: String, action: () -> Unit): MaterialButton {
        return MaterialButton(activity).apply {
            this.text = text
            textSize = 13f
            minHeight = dp(activity, 36)
            minimumHeight = dp(activity, 36)
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(activity, 8) }
        }
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

    private fun disablePublicNotesStorage(activity: MainActivity, binding: ActivityMainBinding) {
        binding.storageSettingsSection.usePublicNotesFolderCheckBox.isChecked = false
        binding.settingsApplicationGroup.applicationUsePublicNotesFolderCheckBox.isChecked = false
        saveCurrentConfig(activity, binding) { it.copy(usePublicNotesFolder = false) }
        refresh(activity, binding)
        showToast(activity, "Публичната папка е изключена. Новите бележки ще се пазят в частната памет на приложението.")
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

    private fun openFullScreenIntentSettings(activity: MainActivity) {
        openSettings(
            activity,
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            "Изключи Full-screen popup от Android системния екран.",
        )
    }

    private fun openSettings(activity: MainActivity, intent: Intent, message: String) {
        runCatching { activity.startActivity(intent) }
            .onSuccess { showToast(activity, message) }
            .onFailure { showToast(activity, "Не успях да отворя Android настройките: ${it.message.orEmpty()}") }
    }

    private fun showToast(activity: MainActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun permissionStateLabel(row: PermissionRow): String = if (row.active) "активно" else row.inactiveLabel

    private fun hasNotificationPermission(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasPermission(activity: MainActivity, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun canUseFullScreenIntent(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager = activity.getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.canUseFullScreenIntent()
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(activity: MainActivity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private data class PermissionRow(
        val label: String,
        val active: Boolean,
        val inactiveLabel: String = "липсва",
        val onEnable: () -> Unit,
        val onDisable: (() -> Unit)? = null,
    )
}

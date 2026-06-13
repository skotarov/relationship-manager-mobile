package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
        val publicNotesSelected = storage.usePublicNotesFolderCheckBox.isChecked
        val publicNotesGranted = !publicNotesSelected || LocalNotesFileStore.canUsePublicFolder()
        val overlayGranted = Settings.canDrawOverlays(activity)
        val overlaySelected = config.useOverlayPopups || popup.useOverlayPopupsCheckBox.isChecked
        val overlayRequired = overlaySelected
        val callScreeningSelected = permissions.useCallScreeningCheckBox.isChecked
        val callScreeningGranted = MainPermissionChecks.hasCallScreeningRole(activity)
        val fullscreenGranted = canUseFullScreenIntent(activity)

        val rows = buildList {
            add(
                PermissionRow("Notifications", notificationsGranted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.requestAppPermissionFromSummary(Manifest.permission.POST_NOTIFICATIONS, "Notifications")
                    }
                }
            )
            add(PermissionRow("Phone", phoneGranted) { activity.requestAppPermissionFromSummary(Manifest.permission.READ_PHONE_STATE, "Phone") })
            add(PermissionRow("Call report log", callLogGranted) { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CALL_LOG, "Call log") })
            add(PermissionRow("Contacts read", contactsGranted) { activity.requestAppPermissionFromSummary(Manifest.permission.READ_CONTACTS, "Contacts read") })
            add(PermissionRow("Contacts write", contactsWriteGranted) { activity.requestAppPermissionFromSummary(Manifest.permission.WRITE_CONTACTS, "Contacts write") })
            add(
                PermissionRow(if (publicNotesSelected) "Public notes folder" else "Private notes storage", publicNotesGranted) {
                    activity.requestPublicNotesStoragePermissionFromSummary()
                }
            )
            add(PermissionRow("Display over other apps", !overlayRequired || overlayGranted) { activity.requestOverlayPermissionFromSummary() })
            add(PermissionRow("Call screening", !callScreeningSelected || callScreeningGranted) { activity.requestCallScreeningPermissionFromSummary() })
            add(PermissionRow("Full-screen popup", fullscreenGranted) { activity.requestFullScreenIntentPermissionFromSummary() })
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
                    text = "${row.label}: ${permissionStateLabel(row.active)}"
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

            if (!row.active) {
                addView(
                    MaterialButton(activity).apply {
                        text = "Включи"
                        textSize = 13f
                        minHeight = dp(activity, 36)
                        minimumHeight = dp(activity, 36)
                        setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                        setOnClickListener { row.onEnable() }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(activity, 8) }
                    }
                )
            }
        }
    }

    private fun permissionStateLabel(active: Boolean): String = if (active) "активно" else "липсва"
    private fun hasNotificationPermission(activity: MainActivity): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
    private fun hasPermission(activity: MainActivity, permission: String): Boolean = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

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
        val onEnable: () -> Unit,
    )
}

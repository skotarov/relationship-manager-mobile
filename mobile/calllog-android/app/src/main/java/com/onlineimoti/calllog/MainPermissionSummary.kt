package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
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
        val publicNotesGranted = LocalNotesFileStore.canUsePublicFolder()
        val overlayGranted = Settings.canDrawOverlays(activity)
        val overlaySelected = config.useOverlayPopups || popup.useOverlayPopupsCheckBox.isChecked
        val overlayRequired = overlaySelected
        val callScreeningGranted = MainPermissionChecks.hasCallScreeningRole(activity)
        val fullscreenGranted = canUseFullScreenIntent(activity)

        val rows = buildList {
            add("Notifications" to notificationsGranted)
            add("Phone" to phoneGranted)
            add("Call report log" to callLogGranted)
            add("Contacts read" to contactsGranted)
            add("Contacts write" to contactsWriteGranted)
            add("Public notes folder" to publicNotesGranted)
            add("Display over other apps" to (!overlayRequired || overlayGranted))
            add("Call screening" to callScreeningGranted)
            add("Full-screen popup" to fullscreenGranted)
        }

        val missingColor = ContextCompat.getColor(activity, R.color.calllog_error)
        val builder = SpannableStringBuilder()
        rows.forEachIndexed { index, row ->
            val start = builder.length
            builder.append("${row.first}: ${permissionStateLabel(row.second)}")
            if (!row.second) builder.setSpan(ForegroundColorSpan(missingColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index < rows.lastIndex) builder.append('\n')
        }
        permissions.permissionsSummaryText.text = builder

        val overlayMissing = overlaySelected && !overlayGranted
        popup.overlayPermissionWarningText.visibility = if (overlayMissing) View.VISIBLE else View.GONE
        popup.useCustomStartPopupCheckBox.isEnabled = !overlayMissing
        popup.useCustomEndPopupCheckBox.isEnabled = !overlayMissing
        popup.overlayPopupOptionsGroup.alpha = if (overlayMissing) 0.55f else 1f

        val needsAppPermissions = !notificationsGranted || !phoneGranted || !callLogGranted || !contactsGranted || !contactsWriteGranted || !publicNotesGranted
        permissions.openAppPermissionsButton.visibility = if (needsAppPermissions) View.VISIBLE else View.GONE
        permissions.openOverlayPermissionButton.visibility = if (overlayRequired && !overlayGranted) View.VISIBLE else View.GONE
        permissions.openCallScreeningButton.visibility = if (callScreeningGranted) View.GONE else View.VISIBLE
        permissions.openFullscreenIntentButton.visibility = if (fullscreenGranted) View.GONE else View.VISIBLE
    }

    private fun permissionStateLabel(active: Boolean): String = if (active) "активно" else "липсва"
    private fun hasNotificationPermission(activity: MainActivity): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
    private fun hasPermission(activity: MainActivity, permission: String): Boolean = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun canUseFullScreenIntent(activity: MainActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val notificationManager = activity.getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.canUseFullScreenIntent()
    }
}

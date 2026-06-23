package com.onlineimoti.calllog

import android.content.Intent
import android.provider.Settings
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class DefaultSmsSettingsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val requestDefaultRole: () -> Unit,
    private val requestSmsPermissions: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    fun wire() {
        val sms = binding.settingsRmContactsGroup.defaultSmsSection
        sms.defaultSmsRoleButton.setOnClickListener {
            if (SmsRoleController.isDefaultSmsApp(activity)) {
                requestSmsPermissions()
                setStatus(activity.getString(R.string.default_sms_permissions_checked))
            } else {
                requestDefaultRole()
            }
        }
        sms.manageDefaultSmsButton.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    fun refresh() {
        val sms = binding.settingsRmContactsGroup.defaultSmsSection
        val active = SmsRoleController.isDefaultSmsApp(activity)
        sms.defaultSmsStatusText.text = activity.getString(
            if (active) R.string.default_sms_status_active else R.string.default_sms_status_inactive,
        )
        sms.defaultSmsRoleButton.text = activity.getString(
            if (active) R.string.default_sms_permissions_button else R.string.default_sms_activate_button,
        )
    }
}

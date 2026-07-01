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
    private val crmSms
        get() = binding.settingsRmContactsGroup.defaultSmsSection

    private val statusSms
        get() = binding.settingsApplicationGroup.permissionsSection.statusSmsPermissionsSection

    fun wire() {
        crmSms.defaultSmsRoleButton.setOnClickListener { requestRoleOrPermissions() }
        crmSms.manageDefaultSmsButton.setOnClickListener { openDefaultSmsSettings() }
        statusSms.statusSmsPermissionsRoleButton.setOnClickListener { requestRoleOrPermissions() }
        statusSms.statusManageDefaultSmsButton.setOnClickListener { openDefaultSmsSettings() }
    }

    fun refresh() {
        val active = SmsRoleController.isDefaultSmsApp(activity)
        val statusText = activity.getString(
            if (active) R.string.default_sms_status_active else R.string.default_sms_status_inactive,
        )
        val roleButtonText = activity.getString(
            if (active) R.string.default_sms_permissions_button else R.string.default_sms_activate_button,
        )

        crmSms.defaultSmsStatusText.text = statusText
        crmSms.defaultSmsRoleButton.text = roleButtonText
        statusSms.statusSmsPermissionsStatusText.text = statusText
        statusSms.statusSmsPermissionsRoleButton.text = roleButtonText
    }

    private fun requestRoleOrPermissions() {
        if (SmsRoleController.isDefaultSmsApp(activity)) {
            requestSmsPermissions()
            setStatus(activity.getString(R.string.default_sms_permissions_checked))
        } else {
            requestDefaultRole()
        }
    }

    private fun openDefaultSmsSettings() {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }
}

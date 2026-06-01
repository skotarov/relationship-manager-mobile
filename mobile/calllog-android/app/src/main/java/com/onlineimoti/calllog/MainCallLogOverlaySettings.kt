package com.onlineimoti.calllog

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainCallLogOverlaySettings {
    fun hydrate(activity: AppCompatActivity, binding: ActivityMainBinding) {
        val callLog = binding.callLogSettingsSection
        val settings = CallLogOverlaySettings.load(activity)
        callLog.useCallLogOverlayButtonCheckBox.isChecked = settings.enabled
        callLog.callLogOverlayDebugCheckBox.isChecked = settings.debugEnabled
        when (settings.position) {
            CallLogOverlaySettings.POSITION_TOP_START -> callLog.callLogOverlayPositionTopStart.isChecked = true
            CallLogOverlaySettings.POSITION_BOTTOM_END -> callLog.callLogOverlayPositionBottomEnd.isChecked = true
            CallLogOverlaySettings.POSITION_BOTTOM_START -> callLog.callLogOverlayPositionBottomStart.isChecked = true
            else -> callLog.callLogOverlayPositionTopEnd.isChecked = true
        }
    }

    fun wire(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        saveOverlaySettings: () -> Unit,
        requestOverlayPermissionIfNeeded: () -> Unit,
        setStatus: (String) -> Unit,
    ) {
        val callLog = binding.callLogSettingsSection
        callLog.openCallLogOverlayAccessButton.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            setStatus("В Accessibility включи Relation Management, за да се показва бутонът върху системния call log.")
        }
        callLog.useCallLogOverlayButtonCheckBox.setOnCheckedChangeListener { _, isChecked ->
            saveOverlaySettings()
            if (isChecked) requestOverlayPermissionIfNeeded()
        }
        callLog.callLogOverlayDebugCheckBox.setOnCheckedChangeListener { _, _ -> saveOverlaySettings() }
        callLog.callLogOverlayButtonPositionGroup.setOnCheckedChangeListener { _, _ -> saveOverlaySettings() }
    }

    fun save(activity: AppCompatActivity, binding: ActivityMainBinding, suppressAutoSave: Boolean) {
        if (suppressAutoSave) return
        val callLog = binding.callLogSettingsSection
        val position = when {
            callLog.callLogOverlayPositionTopStart.isChecked -> CallLogOverlaySettings.POSITION_TOP_START
            callLog.callLogOverlayPositionBottomEnd.isChecked -> CallLogOverlaySettings.POSITION_BOTTOM_END
            callLog.callLogOverlayPositionBottomStart.isChecked -> CallLogOverlaySettings.POSITION_BOTTOM_START
            else -> CallLogOverlaySettings.POSITION_TOP_END
        }
        CallLogOverlaySettings.save(
            activity,
            CallLogOverlayButtonSettings(
                enabled = callLog.useCallLogOverlayButtonCheckBox.isChecked,
                position = position,
                debugEnabled = callLog.callLogOverlayDebugCheckBox.isChecked,
            )
        )
    }
}

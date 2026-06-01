package com.onlineimoti.calllog

import android.content.Intent
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal object MainCallLogOverlaySettings {
    fun hydrate(activity: AppCompatActivity, binding: ActivityMainBinding) {
        val overlay = binding.callLogOverlaySettingsSection
        val settings = CallLogOverlaySettings.load(activity)
        overlay.useCallLogOverlayButtonCheckBox.isChecked = settings.enabled
        overlay.callLogOverlayDebugCheckBox.isChecked = settings.debugEnabled
        when (settings.position) {
            CallLogOverlaySettings.POSITION_TOP_START -> overlay.callLogOverlayPositionTopStart.isChecked = true
            CallLogOverlaySettings.POSITION_BOTTOM_END -> overlay.callLogOverlayPositionBottomEnd.isChecked = true
            CallLogOverlaySettings.POSITION_BOTTOM_START -> overlay.callLogOverlayPositionBottomStart.isChecked = true
            else -> overlay.callLogOverlayPositionTopEnd.isChecked = true
        }
    }

    fun wire(
        activity: AppCompatActivity,
        binding: ActivityMainBinding,
        saveOverlaySettings: () -> Unit,
        requestOverlayPermissionIfNeeded: () -> Unit,
        setStatus: (String) -> Unit,
    ) {
        val overlay = binding.callLogOverlaySettingsSection
        overlay.openCallLogOverlayAccessButton.setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            setStatus("В Accessibility включи Relation Management, за да се показва бутонът върху системния call log.")
        }
        overlay.useCallLogOverlayButtonCheckBox.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            saveOverlaySettings()
            if (isChecked) requestOverlayPermissionIfNeeded()
        }
        overlay.callLogOverlayDebugCheckBox.setOnCheckedChangeListener { _: CompoundButton, _: Boolean -> saveOverlaySettings() }
        overlay.callLogOverlayButtonPositionGroup.setOnCheckedChangeListener { _: RadioGroup, _: Int -> saveOverlaySettings() }
    }

    fun save(activity: AppCompatActivity, binding: ActivityMainBinding, suppressAutoSave: Boolean) {
        if (suppressAutoSave) return
        val overlay = binding.callLogOverlaySettingsSection
        val position = when {
            overlay.callLogOverlayPositionTopStart.isChecked -> CallLogOverlaySettings.POSITION_TOP_START
            overlay.callLogOverlayPositionBottomEnd.isChecked -> CallLogOverlaySettings.POSITION_BOTTOM_END
            overlay.callLogOverlayPositionBottomStart.isChecked -> CallLogOverlaySettings.POSITION_BOTTOM_START
            else -> CallLogOverlaySettings.POSITION_TOP_END
        }
        CallLogOverlaySettings.save(
            activity,
            CallLogOverlayButtonSettings(
                enabled = overlay.useCallLogOverlayButtonCheckBox.isChecked,
                position = position,
                debugEnabled = overlay.callLogOverlayDebugCheckBox.isChecked,
            )
        )
    }
}
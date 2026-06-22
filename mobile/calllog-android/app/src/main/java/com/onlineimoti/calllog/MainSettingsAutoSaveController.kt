package com.onlineimoti.calllog

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import com.google.android.material.textfield.TextInputEditText
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainSettingsAutoSaveController(
    private val binding: ActivityMainBinding,
    private val autoSaveSettings: () -> AppConfig,
    private val applyLanguageIfChanged: (String) -> Unit,
    private val requestOverlayPermissionIfNeeded: () -> Unit,
    private val requestCallScreeningRoleIfNeeded: () -> Unit,
    private val requestPublicNotesStoragePermission: () -> Unit,
) {
    private var syncingDuplicateSettings = false

    fun wire() {
        val application = binding.settingsApplicationGroup
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.callLogSettingsSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val language = binding.languageSettingsSection
        val permissions = binding.permissionsSection
        val tests = binding.testsSection

        remote.remoteEnabledCheckBox.setOnCheckedChangeListener { _, isChecked ->
            remote.remoteSettingsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            autoSaveSettings()
        }

        listOf(
            remote.baseUrlInput,
            remote.accessTokenInput,
            remote.lookupPathInput,
            remote.historyPathInput,
            popup.postCallTimeoutInput,
            callLog.homeCallPageSizeInput,
            popupFilter.contactGroupsInput,
        ).forEach { input -> input.autoSaveTextChanges() }

        popup.postCallEndActionGroup.setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
        popup.useOverlayPopupsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingDuplicateSettings) return@setOnCheckedChangeListener
            syncDuplicateCheckBox(application.applicationUseOverlayPopupsCheckBox, isChecked)
            popup.overlayPopupOptionsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            autoSaveSettings()
            if (isChecked) requestOverlayPermissionIfNeeded()
        }
        application.applicationUseOverlayPopupsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingDuplicateSettings) return@setOnCheckedChangeListener
            syncDuplicateCheckBox(popup.useOverlayPopupsCheckBox, isChecked)
            popup.overlayPopupOptionsGroup.visibility = if (isChecked) View.VISIBLE else View.GONE
            autoSaveSettings()
            if (isChecked) requestOverlayPermissionIfNeeded()
        }
        popup.useCustomStartPopupCheckBox.autoSaveCheckedChanges()
        popup.useCustomEndPopupCheckBox.autoSaveCheckedChanges()

        contactLink.showCrmActionButtonsCheckBox.autoSaveCheckedChanges()
        contactLink.showBulkContactSyncNotificationsCheckBox.autoSaveCheckedChanges()

        language.appLanguageGroup.setOnCheckedChangeListener { _, _ ->
            val config = autoSaveSettings()
            applyLanguageIfChanged(config.appLanguage)
        }
        storage.usePublicNotesFolderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingDuplicateSettings) return@setOnCheckedChangeListener
            syncDuplicateCheckBox(application.applicationUsePublicNotesFolderCheckBox, isChecked)
            autoSaveSettings()
            if (isChecked) requestPublicNotesStoragePermission()
        }
        application.applicationUsePublicNotesFolderCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingDuplicateSettings) return@setOnCheckedChangeListener
            syncDuplicateCheckBox(storage.usePublicNotesFolderCheckBox, isChecked)
            autoSaveSettings()
            if (isChecked) requestPublicNotesStoragePermission()
        }

        popupFilter.notifyUnknownContactsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyKnownContactsCheckBox.autoSaveCheckedChanges()

        permissions.useCallScreeningCheckBox.setOnCheckedChangeListener { _, isChecked ->
            autoSaveSettings()
            if (isChecked) requestCallScreeningRoleIfNeeded()
        }

        tests.showRmDebugBoxCheckBox.autoSaveCheckedChanges()
    }

    private fun TextInputEditText.autoSaveTextChanges() {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                autoSaveSettings()
            }
        })
    }

    private fun CompoundButton.autoSaveCheckedChanges() {
        setOnCheckedChangeListener { _, _ -> autoSaveSettings() }
    }

    private fun syncDuplicateCheckBox(checkBox: CompoundButton, checked: Boolean) {
        if (checkBox.isChecked == checked) return
        syncingDuplicateSettings = true
        checkBox.isChecked = checked
        syncingDuplicateSettings = false
    }
}
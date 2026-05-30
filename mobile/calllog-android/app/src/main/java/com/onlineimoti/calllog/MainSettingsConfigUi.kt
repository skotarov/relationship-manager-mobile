package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityMainBinding

object MainSettingsConfigUi {
    fun hydrate(binding: ActivityMainBinding, config: AppConfig) {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val contactFilter = binding.contactFilterSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val permissions = binding.permissionsSection

        remote.remoteEnabledCheckBox.isChecked = config.remoteEnabled
        remote.remoteSettingsGroup.visibility = if (config.remoteEnabled) View.VISIBLE else View.GONE
        remote.baseUrlInput.setText(config.baseUrl)
        remote.accessTokenInput.setText(config.accessToken)
        contactFilter.contactGroupsInput.setText(config.contactGroups)
        contactFilter.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        contactFilter.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        contactFilter.homeCallPageSizeInput.setText(config.homeCallPageSize.toString())
        remote.lookupPathInput.setText(config.lookupPath)
        remote.formPathInput.setText(config.formPath)
        remote.historyPathInput.setText(config.historyPath)
        popup.postCallTimeoutInput.setText(config.postCallPromptTimeoutSeconds.toString())
        when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> popup.postCallEndActionHistory.isChecked = true
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> popup.postCallEndActionNothing.isChecked = true
            else -> popup.postCallEndActionEdit.isChecked = true
        }
        popup.useOverlayPopupsCheckBox.isChecked = config.useOverlayPopups
        popup.overlayPopupOptionsGroup.visibility = if (config.useOverlayPopups) View.VISIBLE else View.GONE
        popup.useCustomStartPopupCheckBox.isChecked = config.useCustomStartPopup
        popup.useCustomEndPopupCheckBox.isChecked = config.useCustomEndPopup
        if (config.contactLinkMode == ConfigStore.CONTACT_LINK_MODE_CONTACT) {
            contactLink.contactLinkModeContact.isChecked = true
        } else {
            contactLink.contactLinkModeApp.isChecked = true
        }
        contactLink.showCrmActionButtonsCheckBox.isChecked = config.showCrmActionButtons
        storage.usePublicNotesFolderCheckBox.isChecked = config.usePublicNotesFolder
        permissions.useCallScreeningCheckBox.isChecked = config.useCallScreening
    }

    fun read(binding: ActivityMainBinding): AppConfig {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val contactFilter = binding.contactFilterSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val permissions = binding.permissionsSection

        return AppConfig(
            remoteEnabled = remote.remoteEnabledCheckBox.isChecked,
            baseUrl = remote.baseUrlInput.text?.toString().orEmpty(),
            accessToken = remote.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = contactFilter.contactGroupsInput.text?.toString().orEmpty(),
            notifyUnknownContacts = contactFilter.notifyUnknownContactsCheckBox.isChecked,
            notifyKnownContacts = contactFilter.notifyKnownContactsCheckBox.isChecked,
            homeCallPageSize = contactFilter.homeCallPageSizeInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_HOME_CALL_PAGE_SIZE,
            lookupPath = remote.lookupPathInput.text?.toString().orEmpty(),
            formPath = remote.formPathInput.text?.toString().orEmpty(),
            historyPath = remote.historyPathInput.text?.toString().orEmpty(),
            postCallPromptTimeoutSeconds = popup.postCallTimeoutInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS,
            useOverlayPopups = popup.useOverlayPopupsCheckBox.isChecked,
            useCustomStartPopup = popup.useCustomStartPopupCheckBox.isChecked,
            useCustomEndPopup = popup.useCustomEndPopupCheckBox.isChecked,
            postCallEndAction = when {
                popup.postCallEndActionHistory.isChecked -> ConfigStore.POST_CALL_END_ACTION_HISTORY
                popup.postCallEndActionNothing.isChecked -> ConfigStore.POST_CALL_END_ACTION_NOTHING
                else -> ConfigStore.POST_CALL_END_ACTION_EDIT
            },
            contactLinkMode = if (contactLink.contactLinkModeContact.isChecked) {
                ConfigStore.CONTACT_LINK_MODE_CONTACT
            } else {
                ConfigStore.CONTACT_LINK_MODE_APP
            },
            showCrmActionButtons = contactLink.showCrmActionButtonsCheckBox.isChecked,
            usePublicNotesFolder = storage.usePublicNotesFolderCheckBox.isChecked,
            useCallScreening = permissions.useCallScreeningCheckBox.isChecked,
        )
    }
}
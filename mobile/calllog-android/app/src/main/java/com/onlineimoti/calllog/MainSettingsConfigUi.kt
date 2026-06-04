package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityMainBinding

object MainSettingsConfigUi {
    fun hydrate(binding: ActivityMainBinding, config: AppConfig) {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.callLogSettingsSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val language = binding.languageSettingsSection
        val permissions = binding.permissionsSection
        val tests = binding.testsSection

        remote.remoteEnabledCheckBox.isChecked = config.remoteEnabled
        remote.remoteSettingsGroup.visibility = if (config.remoteEnabled) View.VISIBLE else View.GONE
        remote.baseUrlInput.setText(config.baseUrl)
        remote.accessTokenInput.setText(config.accessToken)
        popupFilter.contactGroupsInput.setText(config.contactGroups)
        popupFilter.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        popupFilter.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        callLog.homeCallPageSizeInput.setText(config.homeCallPageSize.toString())
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
        when (config.appLanguage) {
            ConfigStore.LANGUAGE_BG -> language.appLanguageBg.isChecked = true
            ConfigStore.LANGUAGE_EN -> language.appLanguageEn.isChecked = true
            else -> language.appLanguageSystem.isChecked = true
        }
        storage.usePublicNotesFolderCheckBox.isChecked = config.usePublicNotesFolder
        permissions.useCallScreeningCheckBox.isChecked = config.useCallScreening
        tests.showRmDebugBoxCheckBox.isChecked = config.showRmDebugBox
    }

    fun read(binding: ActivityMainBinding): AppConfig {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.callLogSettingsSection
        val contactLink = binding.contactLinkSection
        val storage = binding.storageSettingsSection
        val language = binding.languageSettingsSection
        val permissions = binding.permissionsSection
        val tests = binding.testsSection

        return AppConfig(
            remoteEnabled = remote.remoteEnabledCheckBox.isChecked,
            baseUrl = remote.baseUrlInput.text?.toString().orEmpty(),
            accessToken = remote.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = popupFilter.contactGroupsInput.text?.toString().orEmpty(),
            notifyUnknownContacts = popupFilter.notifyUnknownContactsCheckBox.isChecked,
            notifyKnownContacts = popupFilter.notifyKnownContactsCheckBox.isChecked,
            homeCallPageSize = callLog.homeCallPageSizeInput.text?.toString()?.toIntOrNull()
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
            appLanguage = when {
                language.appLanguageBg.isChecked -> ConfigStore.LANGUAGE_BG
                language.appLanguageEn.isChecked -> ConfigStore.LANGUAGE_EN
                else -> ConfigStore.LANGUAGE_SYSTEM
            },
            usePublicNotesFolder = storage.usePublicNotesFolderCheckBox.isChecked,
            useCallScreening = permissions.useCallScreeningCheckBox.isChecked,
            showRmDebugBox = tests.showRmDebugBoxCheckBox.isChecked,
        )
    }
}

package com.onlineimoti.calllog

import android.view.View
import com.onlineimoti.calllog.databinding.ActivityMainBinding

object MainSettingsConfigUi {
    fun hydrate(binding: ActivityMainBinding, config: AppConfig) {
        binding.remoteEnabledCheckBox.isChecked = config.remoteEnabled
        binding.remoteSettingsGroup.visibility = if (config.remoteEnabled) View.VISIBLE else View.GONE
        binding.baseUrlInput.setText(config.baseUrl)
        binding.accessTokenInput.setText(config.accessToken)
        binding.contactGroupsInput.setText(config.contactGroups)
        binding.notifyUnknownContactsCheckBox.isChecked = config.notifyUnknownContacts
        binding.notifyKnownContactsCheckBox.isChecked = config.notifyKnownContacts
        binding.lookupPathInput.setText(config.lookupPath)
        binding.formPathInput.setText(config.formPath)
        binding.historyPathInput.setText(config.historyPath)
        binding.postCallTimeoutInput.setText(config.postCallPromptTimeoutSeconds.toString())
        binding.useCustomStartPopupCheckBox.isChecked = config.useCustomStartPopup
        binding.useCustomEndPopupCheckBox.isChecked = config.useCustomEndPopup
        if (config.contactLinkMode == ConfigStore.CONTACT_LINK_MODE_CONTACT) {
            binding.contactLinkModeContact.isChecked = true
        } else {
            binding.contactLinkModeApp.isChecked = true
        }
    }

    fun read(binding: ActivityMainBinding): AppConfig {
        return AppConfig(
            remoteEnabled = binding.remoteEnabledCheckBox.isChecked,
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = binding.contactGroupsInput.text?.toString().orEmpty(),
            notifyUnknownContacts = binding.notifyUnknownContactsCheckBox.isChecked,
            notifyKnownContacts = binding.notifyKnownContactsCheckBox.isChecked,
            lookupPath = binding.lookupPathInput.text?.toString().orEmpty(),
            formPath = binding.formPathInput.text?.toString().orEmpty(),
            historyPath = binding.historyPathInput.text?.toString().orEmpty(),
            postCallPromptTimeoutSeconds = binding.postCallTimeoutInput.text?.toString()?.toIntOrNull()
                ?: ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS,
            useCustomStartPopup = binding.useCustomStartPopupCheckBox.isChecked,
            useCustomEndPopup = binding.useCustomEndPopupCheckBox.isChecked,
            contactLinkMode = if (binding.contactLinkModeContact.isChecked) {
                ConfigStore.CONTACT_LINK_MODE_CONTACT
            } else {
                ConfigStore.CONTACT_LINK_MODE_APP
            },
        )
    }
}

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
    private val applyFontScaleIfChanged: (Float) -> Unit,
) {
    fun wire() {
        val remote = binding.remoteSettingsSection
        val popup = binding.popupSettingsSection
        val popupFilter = binding.popupContactFilterSection
        val callLog = binding.settingsPopupGroup.callLogSettingsSection
        val defaultSms = binding.settingsRmContactsGroup.defaultSmsSection
        val contactLink = binding.contactLinkSection
        val language = binding.settingsGeneralGroup.languageSettingsSection
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
        popup.useCustomStartPopupCheckBox.autoSaveCheckedChanges()
        popup.useCustomEndPopupCheckBox.autoSaveCheckedChanges()
        defaultSms.useInternalSmsComposerCheckBox.autoSaveCheckedChanges()
        contactLink.showCrmActionButtonsCheckBox.autoSaveCheckedChanges()
        contactLink.showBulkContactSyncNotificationsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyUnknownContactsCheckBox.autoSaveCheckedChanges()
        popupFilter.notifyKnownContactsCheckBox.autoSaveCheckedChanges()
        tests.showRmDebugBoxCheckBox.autoSaveCheckedChanges()
        binding.settingsGeneralGroup.fontScaleGroup.setOnCheckedChangeListener { _, checkedId ->
            autoSaveSettings()
            val scale = when (checkedId) {
                binding.settingsGeneralGroup.fontScaleLargestRadio.id -> AppFontScaleStore.LARGEST
                binding.settingsGeneralGroup.fontScaleLargerRadio.id -> AppFontScaleStore.LARGER
                else -> AppFontScaleStore.NORMAL
            }
            AppFontScaleStore.saveMultiplier(binding.root.context, scale)
            applyFontScaleIfChanged(scale)
        }

        language.appLanguageGroup.setOnCheckedChangeListener { _, _ ->
            val config = autoSaveSettings()
            applyLanguageIfChanged(config.appLanguage)
        }
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
}

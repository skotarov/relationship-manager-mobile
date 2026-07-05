package com.onlineimoti.calllog

import android.view.View
import androidx.activity.result.ActivityResultLauncher
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding

/** Connects the Settings screen's static buttons without adding lifecycle work to MainActivity. */
internal object MainSettingsActionBinder {
    fun wire(
        activity: MainActivity,
        binding: ActivityMainBinding,
        openHome: () -> Unit,
        syncContacts: () -> Unit,
        saveServerSettings: () -> Unit,
        createArchive: () -> Unit,
        restoreArchive: () -> Unit,
        testStart: (() -> Unit)?,
        testEnd: (() -> Unit)?,
    ) {
        binding.backToHomeButton.setOnClickListener { openHome() }
        binding.contactLinkSection.registerAllContactsButton.setOnClickListener { syncContacts() }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener { saveServerSettings() }
        binding.settingsRegistrationGroup.registrationCompanyAccountButton.setOnClickListener {
            RegistrationActions.openCompanyAccount(activity)
        }
        binding.settingsRegistrationGroup.registrationJoinCompanyButton.setOnClickListener {
            RegistrationActions.showJoinDialog(activity)
        }
        binding.settingsRegistrationGroup.registrationInviteColleagueButton.setOnClickListener {
            RegistrationActions.showInviteDialog(activity)
        }
        binding.archiveSettingsSection.createArchiveButton.setOnClickListener { createArchive() }
        binding.archiveSettingsSection.restoreArchiveButton.setOnClickListener { restoreArchive() }
        if (testStart != null && testEnd != null) {
            activity.findViewById<MaterialButton>(R.id.testStartPopupButton).setOnClickListener { testStart() }
            activity.findViewById<MaterialButton>(R.id.testEndPopupButton).setOnClickListener { testEnd() }
        }
    }
}

package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding
import com.onlineimoti.calllog.databinding.SectionArchiveSettingsBinding
import com.onlineimoti.calllog.databinding.SectionCallLogSettingsBinding
import com.onlineimoti.calllog.databinding.SectionContactLinkBinding
import com.onlineimoti.calllog.databinding.SectionLanguageSettingsBinding
import com.onlineimoti.calllog.databinding.SectionPermissionsBinding
import com.onlineimoti.calllog.databinding.SectionPopupContactFilterBinding
import com.onlineimoti.calllog.databinding.SectionPopupSettingsBinding
import com.onlineimoti.calllog.databinding.SectionRemoteSettingsCompactBinding
import com.onlineimoti.calllog.databinding.SectionStorageSettingsBinding
import com.onlineimoti.calllog.databinding.SectionTestsBinding

internal val ActivityMainBinding.languageSettingsSection: SectionLanguageSettingsBinding
    get() = settingsGeneralGroup.languageSettingsSection

internal val ActivityMainBinding.popupSettingsSection: SectionPopupSettingsBinding
    get() = settingsPopupGroup.popupSettingsSection

internal val ActivityMainBinding.popupContactFilterSection: SectionPopupContactFilterBinding
    get() = settingsPopupGroup.popupContactFilterSection

internal val ActivityMainBinding.callLogSettingsSection: SectionCallLogSettingsBinding
    get() = settingsPopupGroup.callLogSettingsSection

internal val ActivityMainBinding.contactLinkSection: SectionContactLinkBinding
    get() = settingsRmContactsGroup.contactLinkSection

internal val ActivityMainBinding.remoteSettingsSection: SectionRemoteSettingsCompactBinding
    get() = settingsServerGroup.remoteSettingsSection

internal val ActivityMainBinding.permissionsSection: SectionPermissionsBinding
    get() = settingsApplicationGroup.permissionsSection

internal val ActivityMainBinding.storageSettingsSection: SectionStorageSettingsBinding
    get() = settingsDataArchiveGroup.storageSettingsSection

internal val ActivityMainBinding.archiveSettingsSection: SectionArchiveSettingsBinding
    get() = settingsDataArchiveGroup.archiveSettingsSection

internal val ActivityMainBinding.testsSection: SectionTestsBinding
    get() = settingsDebugGroup.testsSection

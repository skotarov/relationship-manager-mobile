package com.onlineimoti.calllog

import com.onlineimoti.calllog.databinding.ActivityMainBinding

/**
 * The settings screen is intentionally organised into nested layout groups.
 * These aliases keep the rest of the settings code independent from that
 * visual nesting and prevent direct ViewBinding lookups from breaking when a
 * section is moved between groups.
 */
internal val ActivityMainBinding.languageSettingsSection
    get() = settingsApplicationGroup.languageSettingsSection

internal val ActivityMainBinding.permissionsSection
    get() = settingsApplicationGroup.permissionsSection

internal val ActivityMainBinding.applicationUsePublicNotesFolderCheckBox
    get() = settingsApplicationGroup.applicationUsePublicNotesFolderCheckBox

internal val ActivityMainBinding.applicationUseOverlayPopupsCheckBox
    get() = settingsApplicationGroup.applicationUseOverlayPopupsCheckBox

internal val ActivityMainBinding.popupSettingsSection
    get() = settingsPopupGroup.popupSettingsSection

internal val ActivityMainBinding.popupContactFilterSection
    get() = settingsPopupGroup.popupContactFilterSection

internal val ActivityMainBinding.callLogSettingsSection
    get() = settingsCallLogGroup.callLogSettingsSection

internal val ActivityMainBinding.defaultSmsSection
    get() = settingsRmContactsGroup.defaultSmsSection

internal val ActivityMainBinding.contactLinkSection
    get() = settingsRmContactsGroup.contactLinkSection

internal val ActivityMainBinding.remoteSettingsSection
    get() = settingsServerGroup.remoteSettingsSection

internal val ActivityMainBinding.storageSettingsSection
    get() = settingsDataArchiveGroup.storageSettingsSection

internal val ActivityMainBinding.archiveSettingsSection
    get() = settingsDataArchiveGroup.archiveSettingsSection

internal val ActivityMainBinding.testsSection
    get() = settingsDebugGroup.testsSection

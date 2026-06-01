package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatActivity

internal object MainStorageSettings {
    fun canUsePublicNotesFolder(activity: AppCompatActivity): Boolean {
        return ConfigStore.load(activity).usePublicNotesFolder
    }

    fun disablePublicNotesFolder(activity: AppCompatActivity) {
        val config = ConfigStore.load(activity).copy(usePublicNotesFolder = false)
        ConfigStore.save(activity, config)
    }
}

internal object MainPopupSettings {
    fun disableOverlayPopups(activity: AppCompatActivity) {
        val config = ConfigStore.load(activity).copy(useOverlayPopups = false)
        ConfigStore.save(activity, config)
    }
}

internal object MainPermissionSettings {
    fun disableCallScreening(activity: AppCompatActivity) {
        val config = ConfigStore.load(activity).copy(useCallScreening = false)
        ConfigStore.save(activity, config)
    }
}

package com.onlineimoti.calllog

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    fun applyFromConfig(context: Context) {
        applyLanguage(ConfigStore.load(context).appLanguage)
    }

    fun applyLanguage(language: String) {
        val localeTag = ConfigStore.localeTagForLanguage(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }
}

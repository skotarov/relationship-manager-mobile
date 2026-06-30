package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    fun applyFromConfig(context: Context) {
        applyLanguage(ConfigStore.load(context).appLanguage)
        (context as? Activity)?.window?.decorView?.post { decorView ->
            TranslationManager.applyOverridesToViewTree(context, decorView)
        }
    }

    fun applyLanguage(language: String) {
        val localeTag = ConfigStore.localeTagForLanguage(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }
}

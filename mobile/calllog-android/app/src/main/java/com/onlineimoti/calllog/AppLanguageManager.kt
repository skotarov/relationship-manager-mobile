package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    fun applyFromConfig(context: Context) {
        applyLanguage(ConfigStore.load(context).appLanguage)
        val activity = context as? Activity ?: return
        val decorView = activity.window?.decorView ?: return
        decorView.post {
            TranslationManager.applyOverridesToViewTree(context, decorView)
        }
    }

    fun applyLanguage(language: String) {
        val localeTag = ConfigStore.localeTagForLanguage(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }
}

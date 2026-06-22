package com.onlineimoti.calllog

import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/** Lightweight language check for model/data helpers that do not receive an Android Context. */
internal object AppLocaleText {
    fun isBulgarian(): Boolean {
        val appTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .trim()
            .lowercase(Locale.ROOT)
        return when {
            appTags.startsWith("bg") -> true
            appTags.startsWith("en") -> false
            else -> Locale.getDefault().language.equals("bg", ignoreCase = true)
        }
    }
}

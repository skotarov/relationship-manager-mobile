package com.onlineimoti.calllog

import android.content.Context
import java.util.Locale

/** Stores only edited values. An absent value always falls back to the bundled resource. */
internal object TranslationOverridesStore {
    private const val PREFS_NAME = "relationship_manager_translation_overrides"
    private const val KEY_PREFIX = "translation_override"

    fun valueOrDefault(context: Context, language: String, key: String, defaultValue: String): String {
        return override(context, language, key) ?: defaultValue
    }

    fun override(context: Context, language: String, key: String): String? {
        return preferences(context).getString(preferenceKey(language, key), null)
    }

    fun overrides(context: Context, language: String): Map<String, String> {
        val prefix = "$KEY_PREFIX.${normalizeLanguage(language)}."
        return preferences(context).all
            .asSequence()
            .filter { (key, value) -> key.startsWith(prefix) && value is String }
            .associate { (key, value) -> key.removePrefix(prefix) to value.toString() }
    }

    fun save(context: Context, language: String, key: String, value: String, defaultValue: String) {
        val editor = preferences(context).edit()
        if (value == defaultValue) {
            editor.remove(preferenceKey(language, key))
        } else {
            editor.putString(preferenceKey(language, key), value)
        }
        editor.apply()
    }

    fun clearLanguage(context: Context, language: String) {
        val prefix = "$KEY_PREFIX.${normalizeLanguage(language)}."
        val editor = preferences(context).edit()
        preferences(context).all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        editor.apply()
    }

    fun normalizeLanguage(language: String): String =
        if (language.lowercase(Locale.ROOT).startsWith(TranslationEntry.LANGUAGE_BG)) {
            TranslationEntry.LANGUAGE_BG
        } else {
            TranslationEntry.LANGUAGE_EN
        }

    private fun preferenceKey(language: String, key: String): String =
        "$KEY_PREFIX.${normalizeLanguage(language)}.$key"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

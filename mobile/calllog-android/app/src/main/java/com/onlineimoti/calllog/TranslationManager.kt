package com.onlineimoti.calllog

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import java.lang.reflect.Modifier
import java.util.Locale

internal data class TranslationEntry(
    val key: String,
    val resourceId: Int,
    val englishDefault: String,
    val bulgarianDefault: String,
) {
    fun defaultFor(language: String): String = if (language == LANGUAGE_BG) bulgarianDefault else englishDefault

    companion object {
        const val LANGUAGE_BG = "bg"
        const val LANGUAGE_EN = "en"
    }
}

/**
 * Reads every app string resource at runtime, so new strings automatically appear in the editor.
 */
internal object TranslationCatalog {
    @Volatile
    private var cachedEntries: List<TranslationEntry>? = null

    fun entries(context: Context): List<TranslationEntry> {
        cachedEntries?.let { return it }
        return synchronized(this) {
            cachedEntries ?: buildEntries(context.applicationContext).also { cachedEntries = it }
        }
    }

    private fun buildEntries(context: Context): List<TranslationEntry> {
        return R.string::class.java.fields
            .asSequence()
            .filter { field -> Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                val resourceId = runCatching { field.getInt(null) }.getOrNull() ?: return@mapNotNull null
                val english = localizedString(context, resourceId, TranslationEntry.LANGUAGE_EN) ?: return@mapNotNull null
                val bulgarian = localizedString(context, resourceId, TranslationEntry.LANGUAGE_BG) ?: english
                TranslationEntry(
                    key = field.name,
                    resourceId = resourceId,
                    englishDefault = english,
                    bulgarianDefault = bulgarian,
                )
            }
            .sortedBy { it.key }
            .toList()
    }

    private fun localizedString(context: Context, resourceId: Int, language: String): String? {
        return runCatching {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            context.createConfigurationContext(configuration).resources.getString(resourceId)
        }.getOrNull()
    }
}

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

/** Applies saved text overrides to visible Android views without changing any configuration keys. */
internal object TranslationManager {
    const val EDITOR_CONTAINER_TAG = "translation_editor_container"

    fun activeLanguage(context: Context): String {
        val configuration = context.resources.configuration
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
        return TranslationOverridesStore.normalizeLanguage(locale?.language.orEmpty())
    }

    fun applyOverridesToViewTree(context: Context, root: View) {
        val language = activeLanguage(context)
        val overrides = TranslationOverridesStore.overrides(context, language)
        if (overrides.isEmpty()) return

        val entriesByDefaultText = TranslationCatalog.entries(context)
            .groupBy { it.defaultFor(language) }

        applyToView(context, root, entriesByDefaultText, overrides)
    }

    private fun applyToView(
        context: Context,
        view: View,
        entriesByDefaultText: Map<String, List<TranslationEntry>>,
        overrides: Map<String, String>,
    ) {
        if (view.tag == EDITOR_CONTAINER_TAG) return

        if (view is TextView) {
            translatedText(view.text, entriesByDefaultText, overrides)?.let { translated ->
                view.text = translated
            }
        }

        if (view is TextInputLayout) {
            translatedText(view.hint, entriesByDefaultText, overrides)?.let { translated ->
                view.hint = translated
            }
        }

        translatedText(view.contentDescription, entriesByDefaultText, overrides)?.let { translated ->
            view.contentDescription = translated
        }

        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                applyToView(context, view.getChildAt(index), entriesByDefaultText, overrides)
            }
        }
    }

    private fun translatedText(
        source: CharSequence?,
        entriesByDefaultText: Map<String, List<TranslationEntry>>,
        overrides: Map<String, String>,
    ): String? {
        val sourceText = source?.toString() ?: return null
        val candidates = entriesByDefaultText[sourceText] ?: return null
        return candidates.firstNotNullOfOrNull { entry -> overrides[entry.key] }
    }
}

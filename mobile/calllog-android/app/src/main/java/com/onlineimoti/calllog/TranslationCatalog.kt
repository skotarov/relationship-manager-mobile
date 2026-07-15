package com.onlineimoti.calllog

import android.content.Context
import android.content.res.Configuration
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
 * This method is deliberately safe to call from a background worker.
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

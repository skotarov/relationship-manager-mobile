package com.onlineimoti.calllog

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser

private data class ViewStringBinding(
    val textResourceId: Int = 0,
    val hintResourceId: Int = 0,
    val contentDescriptionResourceId: Int = 0,
) {
    fun merge(other: ViewStringBinding): ViewStringBinding = ViewStringBinding(
        textResourceId = textResourceId.takeIf { it != 0 } ?: other.textResourceId,
        hintResourceId = hintResourceId.takeIf { it != 0 } ?: other.hintResourceId,
        contentDescriptionResourceId = contentDescriptionResourceId.takeIf { it != 0 } ?: other.contentDescriptionResourceId,
    )

    fun hasAnyValue(): Boolean = textResourceId != 0 || hintResourceId != 0 || contentDescriptionResourceId != 0
}

/** Maps XML view IDs to their exact @string references, avoiding collisions between identical words. */
private object TranslationViewBindingCatalog {
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    @Volatile
    private var cachedBindings: Map<Int, ViewStringBinding>? = null

    fun bindings(context: Context): Map<Int, ViewStringBinding> {
        cachedBindings?.let { return it }
        return synchronized(this) {
            cachedBindings ?: buildBindings(context.applicationContext).also { cachedBindings = it }
        }
    }

    private fun buildBindings(context: Context): Map<Int, ViewStringBinding> {
        val bindings = linkedMapOf<Int, ViewStringBinding>()
        R.layout::class.java.fields
            .asSequence()
            .filter { field -> Modifier.isStatic(field.modifiers) && field.type == Int::class.javaPrimitiveType }
            .forEach { field ->
                val layoutId = runCatching { field.getInt(null) }.getOrNull() ?: return@forEach
                readLayoutBindings(context, layoutId, bindings)
            }
        return bindings
    }

    private fun readLayoutBindings(context: Context, layoutId: Int, bindings: MutableMap<Int, ViewStringBinding>) {
        val parser = runCatching { context.resources.getLayout(layoutId) }.getOrNull() ?: return
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val viewId = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "id", View.NO_ID)
                    val binding = ViewStringBinding(
                        textResourceId = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "text", 0),
                        hintResourceId = parser.getAttributeResourceValue(ANDROID_NAMESPACE, "hint", 0),
                        contentDescriptionResourceId = parser.getAttributeResourceValue(
                            ANDROID_NAMESPACE,
                            "contentDescription",
                            0,
                        ),
                    )
                    if (viewId != View.NO_ID && viewId != 0 && binding.hasAnyValue()) {
                        bindings[viewId] = bindings[viewId]?.merge(binding) ?: binding
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // A single malformed or framework-only layout must not prevent the translation editor.
        } finally {
            runCatching { parser.close() }
        }
    }
}

private data class TranslationApplySnapshot(
    val entriesByEnglishDefault: Map<String, List<TranslationEntry>>,
    val entriesByBulgarianDefault: Map<String, List<TranslationEntry>>,
    val keyByResourceId: Map<Int, String>,
    val viewBindings: Map<Int, ViewStringBinding>,
) {
    fun entriesByDefaultFor(language: String): Map<String, List<TranslationEntry>> =
        if (language == TranslationEntry.LANGUAGE_BG) entriesByBulgarianDefault else entriesByEnglishDefault
}

/** Applies saved text overrides without building resource catalogs on the UI thread. */
internal object TranslationManager {
    const val EDITOR_CONTAINER_TAG = "translation_editor_container"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lookupExecutor = Executors.newSingleThreadExecutor()
    private val lookupPreparing = AtomicBoolean(false)
    private val pendingLookupCallbacks = CopyOnWriteArrayList<(TranslationApplySnapshot) -> Unit>()

    @Volatile
    private var cachedSnapshot: TranslationApplySnapshot? = null

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

    /** Starts the one-time resource/layout lookup without blocking the current screen. */
    fun warmUp(context: Context) {
        prepareSnapshotAsync(context.applicationContext) { }
    }

    /**
     * Returns immediately when the catalog is cold. The visible tree is updated on
     * the main thread only after its lookup data has been prepared in the background.
     */
    fun applyOverridesToViewTree(context: Context, root: View) {
        val appContext = context.applicationContext
        val language = activeLanguage(context)
        val overrides = TranslationOverridesStore.overrides(appContext, language)
        if (overrides.isEmpty()) return

        val snapshot = cachedSnapshot
        if (snapshot != null) {
            applyToView(root, snapshot, language, overrides)
            return
        }

        val rootReference = WeakReference(root)
        prepareSnapshotAsync(appContext) { prepared ->
            val target = rootReference.get() ?: return@prepareSnapshotAsync
            if (!target.isAttachedToWindow) return@prepareSnapshotAsync
            val currentOverrides = TranslationOverridesStore.overrides(appContext, language)
            if (currentOverrides.isNotEmpty()) {
                applyToView(target, prepared, language, currentOverrides)
            }
        }
    }

    private fun prepareSnapshotAsync(context: Context, onReady: (TranslationApplySnapshot) -> Unit) {
        cachedSnapshot?.let { ready ->
            mainHandler.post { onReady(ready) }
            return
        }
        pendingLookupCallbacks += onReady
        if (!lookupPreparing.compareAndSet(false, true)) return

        lookupExecutor.execute {
            val prepared = runCatching {
                val entries = TranslationCatalog.entries(context)
                TranslationApplySnapshot(
                    entriesByEnglishDefault = entries.groupBy { it.englishDefault },
                    entriesByBulgarianDefault = entries.groupBy { it.bulgarianDefault },
                    keyByResourceId = entries.associate { it.resourceId to it.key },
                    viewBindings = TranslationViewBindingCatalog.bindings(context),
                )
            }.getOrElse {
                TranslationApplySnapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap())
            }
            cachedSnapshot = prepared
            lookupPreparing.set(false)
            val callbacks = pendingLookupCallbacks.toList()
            pendingLookupCallbacks.clear()
            mainHandler.post {
                callbacks.forEach { callback -> callback(prepared) }
            }
        }
    }

    private fun applyToView(
        view: View,
        snapshot: TranslationApplySnapshot,
        language: String,
        overrides: Map<String, String>,
    ) {
        if (view.tag == EDITOR_CONTAINER_TAG) return
        val binding = snapshot.viewBindings[view.id]
        val entriesByDefaultText = snapshot.entriesByDefaultFor(language)

        if (view is TextView) {
            val translated = overrideForResource(binding?.textResourceId ?: 0, snapshot, overrides)
                ?: translatedText(view.text, entriesByDefaultText, overrides, useFallback = binding?.textResourceId == 0)
            if (translated != null) view.text = translated
        }

        if (view is TextInputLayout) {
            val translated = overrideForResource(binding?.hintResourceId ?: 0, snapshot, overrides)
                ?: translatedText(view.hint, entriesByDefaultText, overrides, useFallback = binding?.hintResourceId == 0)
            if (translated != null) view.hint = translated
        }

        val translatedContentDescription = overrideForResource(
            binding?.contentDescriptionResourceId ?: 0,
            snapshot,
            overrides,
        ) ?: translatedText(
            view.contentDescription,
            entriesByDefaultText,
            overrides,
            useFallback = binding?.contentDescriptionResourceId == 0,
        )
        if (translatedContentDescription != null) view.contentDescription = translatedContentDescription

        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                applyToView(view.getChildAt(index), snapshot, language, overrides)
            }
        }
    }

    private fun overrideForResource(
        resourceId: Int,
        snapshot: TranslationApplySnapshot,
        overrides: Map<String, String>,
    ): String? = snapshot.keyByResourceId[resourceId]?.let(overrides::get)

    private fun translatedText(
        source: CharSequence?,
        entriesByDefaultText: Map<String, List<TranslationEntry>>,
        overrides: Map<String, String>,
        useFallback: Boolean,
    ): String? {
        if (!useFallback) return null
        val sourceText = source?.toString() ?: return null
        val candidates = entriesByDefaultText[sourceText] ?: return null
        return candidates.firstNotNullOfOrNull { entry -> overrides[entry.key] }
    }
}

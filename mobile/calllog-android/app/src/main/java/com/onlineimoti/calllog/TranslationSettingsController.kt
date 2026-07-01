package com.onlineimoti.calllog

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps Settings responsive by loading the catalog only after the Language section
 * is opened and by rendering a small page of editable rows at a time.
 */
internal class TranslationSettingsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)

    private var selectedLanguage = TranslationEntry.LANGUAGE_EN
    private var entries: List<TranslationEntry> = emptyList()
    private var pageIndex = 0
    private var loading = false
    private var wired = false

    private val editor
        get() = binding.settingsGeneralGroup.languageSettingsSection

    fun wire() {
        if (wired) return
        wired = true
        selectedLanguage = TranslationManager.activeLanguage(activity)
        editor.translationLanguageToggle.check(
            if (selectedLanguage == TranslationEntry.LANGUAGE_BG) {
                R.id.translationEditorBgButton
            } else {
                R.id.translationEditorEnButton
            },
        )
        editor.translationLanguageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedLanguage = if (checkedId == R.id.translationEditorBgButton) {
                TranslationEntry.LANGUAGE_BG
            } else {
                TranslationEntry.LANGUAGE_EN
            }
            pageIndex = 0
            if (entries.isNotEmpty()) renderPage()
        }
        editor.translationPreviousPageButton.setOnClickListener {
            if (pageIndex <= 0 || loading) return@setOnClickListener
            pageIndex--
            renderPage()
        }
        editor.translationNextPageButton.setOnClickListener {
            if (loading || pageIndex >= pageCount() - 1) return@setOnClickListener
            pageIndex++
            renderPage()
        }
        editor.applyTranslationOverridesButton.setOnClickListener {
            // The recreated screen applies overrides asynchronously after its normal UI is visible.
            activity.recreate()
        }
        editor.resetSelectedTranslationLanguageButton.setOnClickListener {
            TranslationOverridesStore.clearLanguage(activity, selectedLanguage)
            pageIndex = 0
            if (entries.isNotEmpty()) renderPage()
            activity.recreate()
        }
        showNotLoadedState()
    }

    /** Called only when the user opens the Language settings section. */
    fun onSectionVisible() {
        TranslationManager.warmUp(activity)
        if (entries.isNotEmpty()) {
            renderPage()
        } else {
            loadEntriesAsync()
        }
    }

    fun release() {
        loadGeneration.incrementAndGet()
        catalogExecutor.shutdownNow()
    }

    private fun loadEntriesAsync() {
        if (loading) return
        loading = true
        showLoadingState()
        val expectedGeneration = loadGeneration.incrementAndGet()
        val appContext = activity.applicationContext
        catalogExecutor.execute {
            val loaded = runCatching { TranslationCatalog.entries(appContext) }.getOrDefault(emptyList())
            mainHandler.post {
                val stillCurrent = expectedGeneration == loadGeneration.get() &&
                    !activity.isFinishing &&
                    !activity.isDestroyed
                if (!stillCurrent) return@post
                entries = loaded
                pageIndex = 0
                loading = false
                renderPage()
            }
        }
    }

    private fun showNotLoadedState() {
        editor.translationEditorContainer.removeAllViews()
        editor.translationEditorLoadingRow.visibility = View.GONE
        editor.translationEditorPager.visibility = View.GONE
    }

    private fun showLoadingState() {
        editor.translationEditorContainer.removeAllViews()
        editor.translationEditorStatusText.text = activity.getString(R.string.translation_editor_loading)
        editor.translationEditorLoadingRow.visibility = View.VISIBLE
        editor.translationEditorPager.visibility = View.GONE
    }

    private fun renderPage() {
        if (loading) {
            showLoadingState()
            return
        }
        val container = editor.translationEditorContainer
        container.removeAllViews()
        editor.translationEditorLoadingRow.visibility = View.GONE

        if (entries.isEmpty()) {
            editor.translationEditorPager.visibility = View.GONE
            return
        }

        val overrides = TranslationOverridesStore.overrides(activity, selectedLanguage)
        val start = pageIndex.coerceAtLeast(0) * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, entries.size)
        entries.subList(start, end).forEach { entry ->
            container.addView(createRow(entry, overrides[entry.key] ?: entry.defaultFor(selectedLanguage)))
        }

        val pages = pageCount()
        editor.translationEditorPager.visibility = View.VISIBLE
        editor.translationEditorPageText.text = activity.getString(
            R.string.translation_editor_page,
            pageIndex + 1,
            pages,
            entries.size,
        )
        editor.translationPreviousPageButton.isEnabled = pageIndex > 0
        editor.translationNextPageButton.isEnabled = pageIndex < pages - 1
    }

    private fun pageCount(): Int = maxOf(1, (entries.size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun createRow(entry: TranslationEntry, currentValue: String): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

        row.addView(TextView(activity).apply {
            text = entry.key
            textSize = 12f
            maxLines = 3
            setTextColor(activity.getColor(R.color.calllog_muted_text))
            layoutParams = LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
        })

        val inputLayout = TextInputLayout(activity).apply {
            boxBackgroundColor = activity.getColor(android.R.color.white)
            boxStrokeColor = activity.getColor(R.color.calllog_border)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        val input = TextInputEditText(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            background = null
            minLines = 1
            maxLines = 4
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(currentValue)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                TranslationOverridesStore.save(
                    context = activity,
                    language = selectedLanguage,
                    key = entry.key,
                    value = s?.toString().orEmpty(),
                    defaultValue = entry.defaultFor(selectedLanguage),
                )
            }
        })
        inputLayout.addView(input)
        row.addView(inputLayout)
        return row
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        private const val PAGE_SIZE = 20
    }
}

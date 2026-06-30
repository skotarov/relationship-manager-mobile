package com.onlineimoti.calllog

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class TranslationSettingsController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private var selectedLanguage = TranslationEntry.LANGUAGE_EN

    private val editor
        get() = binding.settingsGeneralGroup.languageSettingsSection

    fun wire() {
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
            renderEntries()
        }
        editor.applyTranslationOverridesButton.setOnClickListener {
            activity.window?.decorView?.let { root ->
                TranslationManager.applyOverridesToViewTree(activity, root)
            }
            activity.recreate()
        }
        editor.resetSelectedTranslationLanguageButton.setOnClickListener {
            TranslationOverridesStore.clearLanguage(activity, selectedLanguage)
            renderEntries()
            activity.window?.decorView?.let { root ->
                TranslationManager.applyOverridesToViewTree(activity, root)
            }
            activity.recreate()
        }
        renderEntries()
        activity.window?.decorView?.post {
            TranslationManager.applyOverridesToViewTree(activity, binding.root)
        }
    }

    private fun renderEntries() {
        val container = editor.translationEditorContainer
        container.removeAllViews()
        TranslationCatalog.entries(activity).forEach { entry ->
            container.addView(createRow(entry))
        }
    }

    private fun createRow(entry: TranslationEntry): LinearLayout {
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
            setText(
                TranslationOverridesStore.valueOrDefault(
                    context = activity,
                    language = selectedLanguage,
                    key = entry.key,
                    defaultValue = entry.defaultFor(selectedLanguage),
                ),
            )
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
}

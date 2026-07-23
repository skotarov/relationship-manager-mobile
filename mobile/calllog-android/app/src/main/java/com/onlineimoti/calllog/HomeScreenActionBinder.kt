package com.onlineimoti.calllog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal object HomeScreenActionBinder {
    private const val BRAND_SHORTCUT_TAG = "relationship_manager_brand_shortcut"

    fun wire(
        activity: AppCompatActivity,
        binding: ActivityHomeBinding,
        openOverflow: () -> Unit,
        openCrmContacts: () -> Unit,
        previousPage: () -> Unit,
        nextPage: () -> Unit,
        isOnLaterPage: () -> Boolean,
        goToFirstPage: () -> Unit,
    ) {
        moveCrmShortcutBesideWordmark(activity, binding)
        binding.settingsButton.setOnClickListener { openOverflow() }
        binding.crmModeButton.setOnClickListener { openCrmContacts() }
        binding.relationshipManagerWordmark.apply {
            contentDescription = activity.getString(R.string.runtime_crm_clients)
            isClickable = true
            isFocusable = true
            setOnClickListener { openCrmContacts() }
        }
        binding.clearFilterButton.visibility = View.GONE
        binding.filteredDialButton.visibility = View.GONE
        binding.previousCallsButton.setOnClickListener { previousPage() }
        binding.nextCallsButton.setOnClickListener { nextPage() }
        binding.pageText.setOnClickListener {
            if (!isOnLaterPage()) return@setOnClickListener
            AlertDialog.Builder(activity)
                .setTitle("Връщане към началото")
                .setMessage("Да отида ли на страница 1?")
                .setNegativeButton("Отказ", null)
                .setPositiveButton("Да") { _, _ -> goToFirstPage() }
                .show()
        }
    }

    fun updateBrandShortcutVisibility(binding: ActivityHomeBinding, visible: Boolean) {
        val brandContainer = binding.relationshipManagerWordmark.parent as? View ?: return
        if (brandContainer.tag != BRAND_SHORTCUT_TAG) return
        brandContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun moveCrmShortcutBesideWordmark(activity: AppCompatActivity, binding: ActivityHomeBinding) {
        val wordmark = binding.relationshipManagerWordmark
        val headerRow = wordmark.parent as? LinearLayout ?: return
        if (headerRow.tag == BRAND_SHORTCUT_TAG) return

        val wordmarkPosition = headerRow.indexOfChild(wordmark)
        val brandLayoutParams = wordmark.layoutParams as? LinearLayout.LayoutParams ?: return
        val wordmarkHeight = brandLayoutParams.height
        val shortcut = binding.crmControlsScroll
        (shortcut.parent as? ViewGroup)?.removeView(shortcut)
        headerRow.removeView(wordmark)

        val brandContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = BRAND_SHORTCUT_TAG
            addView(
                wordmark,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    wordmarkHeight,
                ),
            )
            addView(shortcut)
            visibility = if (wordmark.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        headerRow.addView(brandContainer, wordmarkPosition, brandLayoutParams)
    }
}

package com.onlineimoti.calllog

import android.content.Context
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

/** Owns Home's search row interactions and debounce timing. */
internal class HomeSearchInputController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val onSearchChanged: (String) -> Unit,
    private val onSearchCleared: () -> Unit,
) {
    private val debounceRunnable = Runnable {
        onSearchChanged(binding.searchInput.text?.toString().orEmpty())
    }

    fun bind() {
        // Android restores EditText contents after Activity.onCreate on some devices,
        // while the row itself can remain at the XML default GONE. Check both now
        // and after the view hierarchy/state restore has settled.
        syncRestoredSearchVisibility()
        binding.searchButton.setOnClickListener { toggle() }
        binding.clearSearchButton.setOnClickListener { clear() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrBlank()) showSearchRow(requestFocus = false)
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, SEARCH_DEBOUNCE_MS)
                syncRestoredSearchVisibility()
            }
        })
    }

    fun cancelPending() {
        handler.removeCallbacks(debounceRunnable)
    }

    fun resetText() {
        cancelPending()
        binding.searchInput.setText("")
        binding.searchRow.visibility = View.GONE
        updateButtonIcon()
    }

    private fun toggle() {
        val show = binding.searchRow.visibility != View.VISIBLE
        if (show) {
            showSearchRow(requestFocus = true)
        } else {
            clear()
            binding.searchRow.visibility = View.GONE
            updateButtonIcon()
        }
    }

    private fun clear() {
        cancelPending()
        binding.searchInput.setText("")
        onSearchCleared()
        updateButtonIcon()
    }

    private fun syncRestoredSearchVisibility() {
        showRestoredSearchIfNeeded()
        binding.root.post { showRestoredSearchIfNeeded() }
        binding.searchInput.post { showRestoredSearchIfNeeded() }
        handler.postDelayed({ showRestoredSearchIfNeeded() }, RESTORE_VISIBILITY_DELAY_MS)
        handler.postDelayed({ showRestoredSearchIfNeeded() }, RESTORE_VISIBILITY_LATE_DELAY_MS)
    }

    private fun showRestoredSearchIfNeeded() {
        if (!binding.searchInput.text.isNullOrBlank()) showSearchRow(requestFocus = false)
        else updateButtonIcon()
    }

    private fun showSearchRow(requestFocus: Boolean) {
        if (binding.searchRow.visibility != View.VISIBLE) binding.searchRow.visibility = View.VISIBLE
        updateButtonIcon()
        if (!requestFocus) return
        binding.searchInput.requestFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
            binding.searchInput,
            InputMethodManager.SHOW_IMPLICIT,
        )
    }

    private fun updateButtonIcon() {
        binding.searchButton.setImageResource(
            if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search,
        )
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val RESTORE_VISIBILITY_DELAY_MS = 120L
        private const val RESTORE_VISIBILITY_LATE_DELAY_MS = 450L
    }
}

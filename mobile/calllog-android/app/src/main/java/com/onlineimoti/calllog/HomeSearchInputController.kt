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
        updateButtonIcon()
        binding.searchButton.setOnClickListener { toggle() }
        binding.clearSearchButton.setOnClickListener { clear() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    fun cancelPending() {
        handler.removeCallbacks(debounceRunnable)
    }

    fun resetText() {
        binding.searchInput.setText("")
    }

    private fun toggle() {
        val show = binding.searchRow.visibility != View.VISIBLE
        if (show) {
            binding.searchRow.visibility = View.VISIBLE
            updateButtonIcon()
            binding.searchInput.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
                binding.searchInput,
                InputMethodManager.SHOW_IMPLICIT,
            )
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

    private fun updateButtonIcon() {
        binding.searchButton.setImageResource(
            if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search,
        )
    }

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}

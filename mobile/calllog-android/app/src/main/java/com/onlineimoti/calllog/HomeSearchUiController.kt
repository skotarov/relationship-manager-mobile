package com.onlineimoti.calllog

import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeSearchUiController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val handler: Handler,
    private val updateRunnable: Runnable,
    private val debounceMs: Long,
    private val onClearState: () -> Unit,
) {
    fun bind() {
        binding.searchButton.setOnClickListener { toggle() }
        binding.clearSearchButton.setOnClickListener { clear() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(updateRunnable)
                handler.postDelayed(updateRunnable, debounceMs)
            }
        })
    }

    fun toggle() {
        val willShow = binding.searchRow.visibility != View.VISIBLE
        if (willShow) {
            binding.searchRow.visibility = View.VISIBLE
            updateButtonIcon()
            binding.searchInput.requestFocus()
            (activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(
                binding.searchInput,
                InputMethodManager.SHOW_IMPLICIT,
            )
        } else {
            clear()
            binding.searchRow.visibility = View.GONE
            updateButtonIcon()
        }
    }

    fun clear() {
        handler.removeCallbacks(updateRunnable)
        binding.searchInput.setText("")
        onClearState()
        updateButtonIcon()
    }

    fun updateButtonIcon() {
        binding.searchButton.setImageResource(
            if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search,
        )
    }
}

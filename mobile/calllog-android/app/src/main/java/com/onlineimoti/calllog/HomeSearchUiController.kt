package com.onlineimoti.calllog

import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

internal class HomeSearchUiController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
) {
    fun toggle(onHideAndClear: () -> Unit) {
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
            onHideAndClear()
            binding.searchRow.visibility = View.GONE
            updateButtonIcon()
        }
    }

    fun updateButtonIcon() {
        binding.searchButton.setImageResource(
            if (binding.searchRow.visibility == View.VISIBLE) R.drawable.ic_popup_close else R.drawable.ic_search,
        )
    }
}

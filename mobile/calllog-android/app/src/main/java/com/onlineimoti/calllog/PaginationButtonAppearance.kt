package com.onlineimoti.calllog

import android.view.View

/** Keeps unavailable paging controls in place without leaving visible text or borders. */
internal object PaginationButtonAppearance {
    fun apply(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0f
    }
}

package com.onlineimoti.calllog

import android.view.View

/** Matches the SMS navigator: unavailable pages remain visible but disabled. */
internal object PaginationButtonAppearance {
    fun apply(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = 1f
    }
}

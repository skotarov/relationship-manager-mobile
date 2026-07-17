package com.onlineimoti.calllog

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Visual-only list density shared by Call Log, History, SMS and CRM lists. */
internal object ListThemeUi {
    fun isCompact(context: Context): Boolean {
        return ConfigStore.load(context.applicationContext).listTheme == ConfigStore.LIST_THEME_COMPACT
    }

    fun applyRowSpacing(
        view: View,
        context: Context,
        dp: (Int) -> Int,
        normalSpacingDp: Int = 8,
    ): View {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return view
        params.bottomMargin = if (isCompact(context)) 0 else dp(normalSpacingDp)
        view.layoutParams = params
        return view
    }
}

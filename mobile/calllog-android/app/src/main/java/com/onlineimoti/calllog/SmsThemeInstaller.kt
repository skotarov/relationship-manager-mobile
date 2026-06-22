package com.onlineimoti.calllog

import android.content.Context

/**
 * Legacy entry point kept for the Settings button.
 *
 * The old implementation built a Xiaomi .mtz ZIP file and opened it as an archive. The home
 * launcher now receives a standard pinned-shortcut request instead, with no theme package or
 * file picker.
 */
internal object SmsThemeInstaller {
    fun isRestoreActionNext(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun openNext(context: Context, setStatus: (String) -> Unit) {
        SmsHomeShortcutInstaller.request(context, setStatus)
    }
}

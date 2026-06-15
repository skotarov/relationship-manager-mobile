package com.onlineimoti.calllog

import android.app.Service
import android.os.Handler

internal class PostCallNavigationActions(
    private val service: Service,
    private val handler: Handler,
    private val phone: () -> String,
    private val title: () -> String,
    private val removeOverlay: () -> Unit,
    private val stopOverlay: () -> Unit,
) {
    fun openContactNotesScreen() {
        val phoneValue = phone()
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        CallNoteEditorLauncher.startHistory(
            context = service,
            phone = phoneValue,
            title = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty().ifBlank { title().ifBlank { phoneValue } },
        )
        stopOverlay()
    }
}

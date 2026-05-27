package com.onlineimoti.calllog

import android.app.Service
import android.content.Intent
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
        service.startActivity(
            Intent(service, ContactNotesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phoneValue)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty().ifBlank { title().ifBlank { phoneValue } })
        )
        stopOverlay()
    }
}

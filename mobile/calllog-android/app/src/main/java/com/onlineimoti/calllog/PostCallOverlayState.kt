package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent

internal data class PostCallOverlayState(
    var formUrl: String = "",
    var phone: String = "",
    var direction: String = "",
    var title: String = "",
    var subtitle: String = "",
    var lines: List<String> = emptyList(),
    var callAt: Long = 0L,
    var durationSeconds: Long = 0L,
    var pendingGeneralNote: String? = null,
    var pendingCallNote: String? = null,
) {
    fun readExtras(intent: Intent?) {
        formUrl = intent?.getStringExtra(PostCallOverlayService.EXTRA_FORM_URL).orEmpty()
        phone = intent?.getStringExtra(PostCallOverlayService.EXTRA_PHONE).orEmpty()
        direction = intent?.getStringExtra(PostCallOverlayService.EXTRA_DIRECTION).orEmpty()
        title = intent?.getStringExtra(PostCallOverlayService.EXTRA_TITLE).orEmpty()
        subtitle = intent?.getStringExtra(PostCallOverlayService.EXTRA_SUBTITLE).orEmpty()
        lines = intent?.getStringArrayListExtra(PostCallOverlayService.EXTRA_LINES).orEmpty()
        callAt = intent?.getLongExtra(PostCallOverlayService.EXTRA_CALL_AT, 0L) ?: 0L
        durationSeconds = intent?.getLongExtra(PostCallOverlayService.EXTRA_DURATION, 0L) ?: 0L
    }

    fun hydrateLatestCallIfNeeded(context: Context) {
        if (callAt > 0L || phone.isBlank()) return
        PhoneCallReader.callsForPhone(context, phone, limit = 1).firstOrNull()?.let { call ->
            callAt = call.startedAt
            durationSeconds = call.durationSeconds
            if (direction.isBlank()) direction = call.direction
        }
    }

    fun clearPendingNotes() {
        pendingGeneralNote = null
        pendingCallNote = null
    }
}

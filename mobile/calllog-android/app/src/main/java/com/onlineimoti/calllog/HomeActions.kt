package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

class HomeActions(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val startTemporaryNoteRefresh: () -> Unit,
) {
    fun openSettings() {
        activity.startActivity(Intent(activity, MainActivity::class.java))
    }

    fun openDialer(number: String) {
        if (number.isBlank()) return
        activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    fun openContactNotesScreen(call: PhoneCallRecord, displayName: String) {
        activity.startActivity(
            Intent(activity, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, call.number)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, displayName.ifBlank { call.number })
        )
    }

    fun openContactNotePopupForCall(call: PhoneCallRecord, displayName: String) {
        if (!Settings.canDrawOverlays(activity)) {
            binding.homeStatusText.text = "За popup бележка разреши 'Показване върху други приложения' от Настройки."
            return
        }
        activity.startService(
            Intent(activity, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, call.number)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, call.direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, displayName)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, call.startedAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, call.durationSeconds)
        )
        startTemporaryNoteRefresh()
    }
}

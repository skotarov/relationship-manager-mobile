package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

internal class HomeNoteSavedReceiverController(
    private val activity: HomeActivity,
    private val onNoteSaved: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            HomeCallPageLoader.clearSearchCache()
            HomeTimelineLoader.invalidateCache()
            onNoteSaved()
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            // Legacy editor result action.
            addAction(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
            // Current popup and editor action, emitted after a note is actually saved.
            addAction(PostCallOverlayService.ACTION_NOTES_CHANGED)
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }
}

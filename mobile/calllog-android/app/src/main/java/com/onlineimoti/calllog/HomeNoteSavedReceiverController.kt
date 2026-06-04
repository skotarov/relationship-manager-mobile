package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

internal class HomeNoteSavedReceiverController(
    private val activity: HomeActivity,
    private val onNoteSaved: () -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            HomeCallPageLoader.clearSearchCache()
            onNoteSaved()
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }
}

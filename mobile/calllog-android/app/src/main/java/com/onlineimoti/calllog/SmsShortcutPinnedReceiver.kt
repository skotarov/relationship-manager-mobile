package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Receives the launcher callback only after the standard Android pin request succeeds. */
class SmsShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "Иконата „Съобщения“ е добавена на началния екран.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

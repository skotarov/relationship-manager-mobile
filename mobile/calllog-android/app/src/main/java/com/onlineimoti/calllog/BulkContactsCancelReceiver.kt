package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BulkContactsCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL_BULK_CONTACTS) return
        BulkContactsTaskRunner.cancel(context)
    }

    companion object {
        const val ACTION_CANCEL_BULK_CONTACTS = "com.onlineimoti.calllog.CANCEL_BULK_CONTACTS"
    }
}

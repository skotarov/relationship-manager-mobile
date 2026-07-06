package com.onlineimoti.calllog

import android.content.Context

internal object HomeCrmContactCandidatesServer {
    fun load(context: Context): List<PhoneCallRecord> =
        ServerCrmContactsClient.lookup(ConfigStore.load(context.applicationContext))
}

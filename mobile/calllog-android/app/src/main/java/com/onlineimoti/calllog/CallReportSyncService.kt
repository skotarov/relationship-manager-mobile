package com.onlineimoti.calllog

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

class CallReportSyncService : Service() {
    private val syncAdapter by lazy { CallReportSyncAdapter(this) }
    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}

private class CallReportSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
    override fun onPerformSync(
        account: Account?,
        extras: Bundle?,
        authority: String?,
        provider: ContentProviderClient?,
        syncResult: SyncResult?,
    ) {
        // No-op. Call Report only exposes contact action metadata.
    }
}

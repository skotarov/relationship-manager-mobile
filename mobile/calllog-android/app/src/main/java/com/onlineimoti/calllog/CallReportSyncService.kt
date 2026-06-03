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
import android.os.Process

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
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        if (authority != android.provider.ContactsContract.AUTHORITY) return
        if (account?.type != CallReportContactIntegration.ACCOUNT_TYPE) return

        val result = BulkContactsTaskRunner.registerAllFromSync(context) ?: return
        syncResult?.stats?.numEntries = result.scanned.toLong()
        syncResult?.stats?.numInserts = result.created.toLong()
        syncResult?.stats?.numUpdates = result.skippedExisting.toLong()
        syncResult?.stats?.numSkippedEntries = result.failed.toLong()
    }
}

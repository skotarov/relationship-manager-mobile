package com.onlineimoti.calllog

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class HomeContactsSyncPreparer(
    private val context: Context,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var prepared = false

    fun prepareOnce() {
        if (prepared) return
        prepared = true
        executor.execute {
            CallReportRuntime.ensureContactsSync(context.applicationContext)
        }
    }

    fun release() {
        executor.shutdownNow()
    }
}

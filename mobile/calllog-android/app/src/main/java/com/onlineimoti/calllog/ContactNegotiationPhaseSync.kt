package com.onlineimoti.calllog

import android.content.Context

/** Reconciles the local contact phase with the company server using last-write-wins timestamps. */
internal object ContactNegotiationPhaseSync {
    /** Returns true only when the visible local state changed from a newer server state. */
    fun synchronize(context: Context, phone: String): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (phone.isBlank() || !CallReportRemoteAccess.isReady(config)) return false

        val before = ContactNegotiationPhaseStore.state(appContext, phone)
        val server = ContactNegotiationPhaseRemoteClient.fetch(config, phone)
        val resolved = if (before.updatedAtMs > server.updatedAtMs) {
            ContactNegotiationPhaseRemoteClient.update(config, phone, before)
        } else {
            server
        }
        val after = ContactNegotiationPhaseStore.applyServerState(appContext, phone, resolved)
        return after != before
    }
}

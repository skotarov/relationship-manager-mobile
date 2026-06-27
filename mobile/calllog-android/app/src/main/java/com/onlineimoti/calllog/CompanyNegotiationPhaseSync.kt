package com.onlineimoti.calllog

import android.content.Context

/** Last-write-wins reconciliation for one phone in one company scope. */
internal object CompanyNegotiationPhaseSync {
    fun synchronize(context: Context, phone: String, companyId: String): Boolean {
        val appContext = context.applicationContext
        val config = ConfigStore.load(appContext)
        if (
            phone.isBlank() ||
            companyId.isBlank() ||
            !CallReportRemoteAccess.isReady(config)
        ) return false

        val before = CompanyNegotiationPhaseStore.state(appContext, phone, companyId)
        val server = CompanyNegotiationPhaseRemoteClient.fetch(config, phone, companyId)
        val resolved = if (before.updatedAtMs > server.updatedAtMs) {
            CompanyNegotiationPhaseRemoteClient.update(config, phone, companyId, before)
        } else {
            server
        }
        val after = CompanyNegotiationPhaseStore.applyServerState(appContext, phone, companyId, resolved)
        return after != before
    }
}

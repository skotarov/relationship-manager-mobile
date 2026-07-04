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

        val hasCompanyState = CompanyNegotiationPhaseStore.hasSavedState(appContext, phone, companyId)
        val before = CompanyNegotiationPhaseStore.state(appContext, phone, companyId)
        val server = CompanyNegotiationPhaseRemoteClient.fetch(config, phone, companyId)
        val uploaded = hasCompanyState && before.updatedAtMs > server.updatedAtMs
        val resolved = if (uploaded) {
            CompanyNegotiationPhaseRemoteClient.update(config, phone, companyId, before)
        } else {
            server
        }
        val after = CompanyNegotiationPhaseStore.applyServerState(appContext, phone, companyId, resolved)
        // Uploading a local phase does not necessarily change the local object,
        // but it does change what Home's server-backed phase filter must show.
        return uploaded || after != before
    }
}

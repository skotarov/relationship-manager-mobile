package com.onlineimoti.calllog

import android.content.Context

internal object CrmContactLinkSaver {
    fun save(
        context: Context,
        fields: CallReportStableCrmContactWriter.Fields,
        mode: String,
        phone: String,
        title: String,
    ): Boolean {
        return when (mode) {
            ConfigStore.CONTACT_LINK_MODE_CONTACT -> CallReportStableCrmContactWriter.save(context, fields)
            else -> CallReportContactIntegration.linkContact(context, phone, title)
        }
    }
}

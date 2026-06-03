package com.onlineimoti.calllog

import android.content.Context

internal object CrmContactLinkSaver {
    fun save(
        context: Context,
        fields: CallReportStableCrmContactWriter.Fields,
        @Suppress("UNUSED_PARAMETER") mode: String,
        @Suppress("UNUSED_PARAMETER") phone: String,
        @Suppress("UNUSED_PARAMETER") title: String,
    ): Boolean {
        return CallReportStableCrmContactWriter.save(context, fields)
    }
}

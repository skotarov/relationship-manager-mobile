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
        val normalizedPhone = PhoneNormalizer.normalize(phone).ifBlank {
            PhoneNormalizer.normalize(fields.originalPhone)
        }
        return when (mode) {
            ConfigStore.CONTACT_LINK_MODE_APP -> {
                // The default mode must register Relationship Manager as a linked app
                // inside the Android contact card, not only as a full RM raw contact.
                val linkedAsApp = CallReportContactIntegration.linkContactAsAppIfMissing(
                    context = context,
                    phone = normalizedPhone,
                )
                if (linkedAsApp) true else CallReportStableCrmContactWriter.save(context, fields)
            }
            ConfigStore.CONTACT_LINK_MODE_CONTACT -> CallReportStableCrmContactWriter.save(context, fields)
            else -> {
                val linkedAsApp = CallReportContactIntegration.linkContactAsAppIfMissing(
                    context = context,
                    phone = normalizedPhone,
                )
                if (linkedAsApp) true else CallReportStableCrmContactWriter.save(context, fields.copy(displayName = fields.displayName.ifBlank { title }))
            }
        }
    }
}

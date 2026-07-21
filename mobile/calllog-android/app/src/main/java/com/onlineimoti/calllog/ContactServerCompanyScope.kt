package com.onlineimoti.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Determines whether a phone may use the server-backed company-note scope.
 * CRM contacts always qualify. Unknown real contacts also qualify so existing
 * server records can be found before the number is saved in Android Contacts.
 */
internal object ContactServerCompanyScope {
    fun isAvailable(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        val crmEnabled = CrmContactSyncStore.isEnabled(context, phone)
        val unknownNumber = !crmEnabled && isUnknownNumber(context, phone)
        return ContactServerCompanyScopePolicy.isAvailable(crmEnabled, unknownNumber)
    }

    fun isUnknownNumber(context: Context, phone: String): Boolean {
        if (phone.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            // Without permission we cannot confirm a real contact, so retain the
            // existing unknown-number behavior and allow the server lookup.
            return true
        }
        return RmRealContactLookup.findContactId(context, phone) <= 0L
    }
}

internal object ContactServerCompanyScopePolicy {
    fun isAvailable(crmEnabled: Boolean, unknownNumber: Boolean): Boolean =
        crmEnabled || unknownNumber
}

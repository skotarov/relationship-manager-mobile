package com.onlineimoti.calllog

import android.content.Context

internal object RmLayerContactDataSyncer {
    /**
     * Единственият централен sync за данните в съществуващ RM layer.
     * Не създава RM contact; само обновява стандартното поле Note,
     * включително основната бележка и последните 3 бележки от разговори.
     */
    fun sync(
        context: Context,
        phone: String,
        noteOverride: String? = null,
        @Suppress("UNUSED_PARAMETER") preserveExistingLayerNote: Boolean = false,
    ): Boolean {
        val appContext = context.applicationContext
        return if (noteOverride != null) {
            RmLayerNoteSyncer.syncIfLayerExists(appContext, phone, noteOverride)
        } else {
            RmLayerNoteSyncer.syncCurrentGeneralNoteIfLayerExists(appContext, phone)
        }
    }
}

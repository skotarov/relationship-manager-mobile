package com.onlineimoti.calllog

import android.content.Context

internal object RmLayerContactDataSyncer {
    /**
     * Единственият централен sync за данните в съществуващ RM layer.
     * Не създава RM contact; само обновява полетата му, ако такъв вече има.
     */
    fun sync(
        context: Context,
        phone: String,
        noteOverride: String? = null,
        preserveExistingLayerNote: Boolean = false,
    ): Boolean {
        val appContext = context.applicationContext
        val noteSynced = when {
            noteOverride != null -> RmLayerNoteSyncer.syncIfLayerExists(appContext, phone, noteOverride)
            preserveExistingLayerNote -> RmLayerNoteSyncer.reformatExistingLayerNote(appContext, phone)
            else -> RmLayerNoteSyncer.syncCurrentGeneralNoteIfLayerExists(appContext, phone)
        }
        val callNotesSynced = RmLayerCallNotesSyncer.syncIfLayerExists(appContext, phone)
        return noteSynced && callNotesSynced
    }
}

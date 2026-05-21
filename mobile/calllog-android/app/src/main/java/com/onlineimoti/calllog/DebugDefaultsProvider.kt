package com.onlineimoti.calllog

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

class DebugDefaultsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context ?: return true
        appContext.getSharedPreferences("callreport_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("notify_known_contacts", true)
            .putInt("post_call_timeout", 6)
            .apply()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

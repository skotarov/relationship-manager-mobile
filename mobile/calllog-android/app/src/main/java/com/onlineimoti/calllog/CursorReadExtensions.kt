package com.onlineimoti.calllog

internal fun android.database.Cursor.getStringOrEmpty(column: String): String {
    val index = getColumnIndex(column)
    return if (index >= 0) getString(index).orEmpty() else ""
}

internal fun android.database.Cursor.getLongOrZero(column: String): Long {
    val index = getColumnIndex(column)
    return if (index >= 0) getLong(index) else 0L
}

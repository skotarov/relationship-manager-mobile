package com.onlineimoti.calllog

import android.graphics.Color

internal fun postCallDirectionColor(direction: String): Int {
    return when (direction) {
        "out" -> Color.rgb(34, 197, 94)
        "in" -> Color.rgb(59, 130, 246)
        else -> Color.rgb(107, 114, 128)
    }
}

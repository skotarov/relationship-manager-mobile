package com.onlineimoti.calllog

import android.content.Intent

internal object ExternalLaunchNavigation {
    fun apply(intent: Intent): Intent {
        return intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
    }
}

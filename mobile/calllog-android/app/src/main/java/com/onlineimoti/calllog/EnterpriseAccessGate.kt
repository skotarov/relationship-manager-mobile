package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.content.Intent

/** Routes the public Play package to company login and the required disclosure. */
internal object EnterpriseAccessGate {
    fun isReady(context: Context): Boolean {
        if (!BuildConfig.IS_PLAY_DISTRIBUTION) return true
        return EnterpriseSessionStore.hasActiveSession(context) &&
            EnterpriseSessionStore.hasAcceptedCallLogDisclosure(context)
    }

    fun redirectIfNeeded(activity: Activity): Boolean {
        if (!BuildConfig.IS_PLAY_DISTRIBUTION) return false
        val destination = when {
            !EnterpriseSessionStore.hasActiveSession(activity) -> EnterpriseLoginActivity::class.java
            !EnterpriseSessionStore.hasAcceptedCallLogDisclosure(activity) -> EnterpriseDisclosureActivity::class.java
            else -> return false
        }
        activity.startActivity(
            Intent(activity, destination).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        activity.finish()
        return true
    }
}

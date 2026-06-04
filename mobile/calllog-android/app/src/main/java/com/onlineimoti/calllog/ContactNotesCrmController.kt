package com.onlineimoti.calllog

import android.app.Activity

class ContactNotesCrmController(
    private val activity: Activity,
    private val getPhone: () -> String,
    private val getTitle: () -> String,
    private val setBusy: (Boolean) -> Unit,
    private val rerender: () -> Unit,
) {
    fun reconcileCurrentPhone() {
        val phone = getPhone()
        if (phone.isBlank()) return
        setBusy(true)
        rerender()
        val appContext = activity.applicationContext
        val displayName = getTitle().takeIf { it != phone }.orEmpty()
        Thread {
            RmContactReconciler.reconcileOne(appContext, phone, displayName)
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    rerender()
                }
            }
        }.start()
    }
}

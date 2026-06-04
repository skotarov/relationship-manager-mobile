package com.onlineimoti.calllog

import android.app.Activity
import android.widget.Toast

class ContactNotesCrmController(
    private val activity: Activity,
    private val getPhone: () -> String,
    private val getTitle: () -> String,
    private val setBusy: (Boolean) -> Unit,
    private val rerender: () -> Unit,
) {
    fun isLinked(): Boolean = CallReportContactIntegration.isContactLinked(activity, getPhone())

    fun reconcileCurrentPhone() {
        val phone = getPhone()
        if (phone.isBlank()) return
        setBusy(true)
        rerender()
        val appContext = activity.applicationContext
        val displayName = getTitle().takeIf { it != phone }.orEmpty()
        Thread {
            val result = RmContactReconciler.reconcileOne(appContext, phone, displayName)
            val message = resultMessage(result)
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    rerender()
                }
            }
        }.start()
    }

    private fun resultMessage(result: RmContactReconcileResult): String {
        return when (result.action) {
            RmContactReconcileAction.ADDED -> "Добавена е RM връзка към контакта"
            RmContactReconcileAction.UPDATED -> "RM връзката е обновена"
            RmContactReconcileAction.DELETED -> "Премахнат е осиротял RM запис"
            RmContactReconcileAction.UNCHANGED -> "RM връзката вече е наред"
            RmContactReconcileAction.SKIPPED -> "Няма намерен Android контакт за този номер"
            RmContactReconcileAction.FAILED -> "Не успях да обновя RM връзката"
        }
    }
}

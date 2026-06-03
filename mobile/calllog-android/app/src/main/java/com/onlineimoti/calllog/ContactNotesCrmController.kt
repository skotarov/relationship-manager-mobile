package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.widget.Toast

class ContactNotesCrmController(
    private val activity: Activity,
    private val getPhone: () -> String,
    private val getTitle: () -> String,
    private val setBusy: (Boolean) -> Unit,
    private val rerender: () -> Unit,
) {
    fun isLinked(): Boolean = CallReportContactIntegration.isContactLinked(activity, getPhone())

    fun toggle(currentlyLinked: Boolean) {
        val phone = getPhone()
        if (phone.isBlank()) return
        if (!currentlyLinked) {
            showDialog()
            return
        }

        setBusy(true)
        rerender()
        val appContext = activity.applicationContext
        Thread {
            val deleted = removeCrmLink(appContext, phone)
            val message = if (deleted > 0) "Премахнато от Call Report контактите" else "Няма намерен Call Report запис"
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    rerender()
                }
            }
        }.start()
    }

    fun showDialog() {
        val phone = getPhone()
        CrmContactFieldsDialog.show(
            activity = activity,
            phone = phone,
            titleText = getTitle(),
            currentGeneralNote = ContactNoteReader.generalNoteForPhone(activity, phone),
            onSave = ::saveFields,
        )
    }

    private fun saveFields(fields: CallReportStableCrmContactWriter.Fields) {
        setBusy(true)
        rerender()
        val appContext = activity.applicationContext
        val mode = ConfigStore.load(activity).contactLinkMode
        val phone = getPhone()
        val title = getTitle()
        Thread {
            val saved = saveCrmLink(appContext, fields, mode, phone, title)
            activity.runOnUiThread {
                setBusy(false)
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, if (saved) "Регистрирано в Call Report контактите" else "Не успях да регистрирам контакта", Toast.LENGTH_SHORT).show()
                    rerender()
                }
            }
        }.start()
    }

    private fun saveCrmLink(
        context: Context,
        fields: CallReportStableCrmContactWriter.Fields,
        mode: String,
        phone: String,
        title: String,
    ): Boolean {
        return CrmContactLinkSaver.save(
            context = context,
            fields = fields,
            mode = mode,
            phone = phone,
            title = title,
        )
    }

    private fun removeCrmLink(context: Context, phone: String): Int {
        return CallReportContactIntegration.removeContact(context, phone)
    }
}

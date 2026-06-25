package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.provider.ContactsContract
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Edits the application's own RM raw-contact layer. It deliberately does not
 * open the default Contacts app and never changes the user's normal contact.
 */
internal class RmContactFormDialog(
    private val activity: Activity,
) {
    fun show(
        phone: String,
        fallbackTitle: String,
        onSaved: () -> Unit,
    ) {
        val normalizedPhone = PhoneNormalizer.normalize(phone)
        if (normalizedPhone.isBlank()) return
        if (!RmContactPermissions.canReadAndWriteContacts(activity)) {
            Toast.makeText(activity, "Разреши достъп за четене и запис на контакти.", Toast.LENGTH_SHORT).show()
            return
        }

        val initial = RmContactFormStore.read(activity, normalizedPhone, fallbackTitle)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val scroll = ScrollView(activity).apply { addView(content) }

        content.addView(label("Основен телефон"))
        content.addView(TextView(activity).apply {
            text = phone
            textSize = 16f
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(dp(2), dp(2), dp(2), dp(8))
        })

        val displayName = textField(content, "Име за показване", initial.displayName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val givenName = textField(content, "Собствено име", initial.givenName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val middleName = textField(content, "Бащино име", initial.middleName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val familyName = textField(content, "Фамилия", initial.familyName, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val additionalPhone = textField(content, "Допълнителен телефон", initial.additionalPhone, InputType.TYPE_CLASS_PHONE)
        val organization = textField(content, "Фирма", initial.organization, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val jobTitle = textField(content, "Длъжност", initial.jobTitle, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val emailWork = textField(content, "Служебен e-mail", initial.emailWork, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val note = textField(
            parent = content,
            title = "Бележка",
            value = initial.note,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            multiLine = true,
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("RM контакт")
            .setMessage("Данните се пазят в RM слоя на контактите.")
            .setView(scroll)
            .setNegativeButton("Откажи", null)
            .setPositiveButton("Запази", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = RmContactFormValues(
                    displayName = displayName.text?.toString().orEmpty().trim(),
                    givenName = givenName.text?.toString().orEmpty().trim(),
                    middleName = middleName.text?.toString().orEmpty().trim(),
                    familyName = familyName.text?.toString().orEmpty().trim(),
                    additionalPhone = additionalPhone.text?.toString().orEmpty().trim(),
                    organization = organization.text?.toString().orEmpty().trim(),
                    jobTitle = jobTitle.text?.toString().orEmpty().trim(),
                    emailWork = emailWork.text?.toString().orEmpty().trim(),
                    note = note.text?.toString().orEmpty().trim(),
                ).withFallbackName(fallbackTitle, normalizedPhone)

                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                saveButton.text = "Записва…"
                Thread {
                    val saved = save(normalizedPhone, values)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (saved) {
                            Toast.makeText(activity, "RM контактът е записан.", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            onSaved()
                        } else {
                            Toast.makeText(activity, "RM контактът не беше записан.", Toast.LENGTH_SHORT).show()
                            saveButton.isEnabled = true
                            saveButton.text = "Запази"
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun save(phone: String, values: RmContactFormValues): Boolean {
        val context = activity.applicationContext
        val saved = CallReportStableCrmContactWriter.save(
            context = context,
            fields = CallReportStableCrmContactWriter.Fields(
                originalPhone = phone,
                displayName = values.displayName,
                additionalPhone = values.additionalPhone,
                organization = values.organization,
                jobTitle = values.jobTitle,
                emailWork = values.emailWork,
                note = values.note,
                groupName = RmContactSyncLayerStore.groupNameForCurrentRules(context, phone),
                givenName = values.givenName,
                middleName = values.middleName,
                familyName = values.familyName,
            ),
        )
        if (saved && CrmContactSyncStore.isEnabled(context, phone)) {
            RmContactSyncLayerStore.applyCloudSyncLabelsIfEnabled(context, phone)
            CallReportSyncScheduler.enqueueCatchUp(context, reason = "rm_contact_form_saved")
        }
        return saved
    }

    private fun textField(
        parent: LinearLayout,
        title: String,
        value: String,
        inputType: Int,
        multiLine: Boolean = false,
    ): EditText {
        parent.addView(label(title))
        return EditText(activity).apply {
            setText(value)
            this.inputType = inputType
            if (multiLine) {
                minLines = 3
                maxLines = 6
                gravity = android.view.Gravity.TOP
            } else {
                isSingleLine = true
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
            parent.addView(this)
        }
    }

    private fun label(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

private data class RmContactFormValues(
    val displayName: String = "",
    val givenName: String = "",
    val middleName: String = "",
    val familyName: String = "",
    val additionalPhone: String = "",
    val organization: String = "",
    val jobTitle: String = "",
    val emailWork: String = "",
    val note: String = "",
) {
    fun withFallbackName(fallbackTitle: String, phone: String): RmContactFormValues {
        val personName = listOf(givenName, middleName, familyName).filter { it.isNotBlank() }.joinToString(" ")
        val fallback = fallbackTitle.trim().takeUnless { it.isBlank() || it == phone }
        return copy(displayName = displayName.ifBlank { personName }.ifBlank { fallback.orEmpty() }.ifBlank { phone })
    }
}

private object RmContactFormStore {
    fun read(context: android.content.Context, phone: String, fallbackTitle: String): RmContactFormValues {
        val fallbackName = fallbackTitle.trim().takeUnless { it.isBlank() || it == phone }.orEmpty()
        val rawId = CrmContactAccountStore.findCallReportRawContactId(context, phone)
        if (rawId <= 0L) return RmContactFormValues(displayName = fallbackName)

        var values = RmContactFormValues(displayName = fallbackName)
        val mimes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
        )
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (${mimes.joinToString(",") { "?" }})"
        val args = arrayOf(rawId.toString(), *mimes)
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA3,
                    ContactsContract.Data.DATA4,
                    ContactsContract.Data.DATA5,
                ),
                selection,
                args,
                null,
            )?.use { cursor ->
                val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
                val data1Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
                val data2Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
                val data3Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
                val data4Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA4)
                val data5Index = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA5)
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val data1 = cursor.getString(data1Index).orEmpty()
                    val data2 = cursor.getString(data2Index).orEmpty()
                    val data3 = cursor.getString(data3Index).orEmpty()
                    val data4 = cursor.getString(data4Index).orEmpty()
                    val data5 = cursor.getString(data5Index).orEmpty()
                    values = when (mime) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> values.copy(
                            displayName = data1.ifBlank { values.displayName },
                            givenName = data2,
                            familyName = data3,
                            middleName = data5,
                        )
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val isAdditionalPhone = data3 == CrmContactAccountStore.EXTRA_PHONE_LABEL
                            if (isAdditionalPhone) values.copy(additionalPhone = data1) else values
                        }
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> values.copy(
                            organization = data1,
                            jobTitle = data4,
                        )
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val type = data2.toIntOrNull() ?: ContactsContract.CommonDataKinds.Email.TYPE_OTHER
                            when {
                                type == ContactsContract.CommonDataKinds.Email.TYPE_WORK -> values.copy(emailWork = data1)
                                values.emailWork.isBlank() -> values.copy(emailWork = data1)
                                else -> values
                            }
                        }
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> values.copy(note = data1)
                        else -> values
                    }
                }
            }
        }
        return values
    }
}

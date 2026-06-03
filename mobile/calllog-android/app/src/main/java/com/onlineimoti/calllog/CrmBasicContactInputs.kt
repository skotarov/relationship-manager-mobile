package com.onlineimoti.calllog

import android.graphics.Color
import android.widget.EditText
import android.widget.LinearLayout

data class CrmBasicContactInputs(
    val name: EditText,
    val additionalPhone: EditText,
    val organization: EditText,
    val jobTitle: EditText,
    val website: EditText,
    val group: EditText,
    val note: EditText,
    val custom: EditText,
) {
    fun toFields(phone: String): CallReportStableCrmContactWriter.Fields {
        return CallReportStableCrmContactWriter.Fields(
            originalPhone = phone,
            displayName = name.text?.toString().orEmpty(),
            additionalPhone = additionalPhone.text?.toString().orEmpty(),
            organization = organization.text?.toString().orEmpty(),
            jobTitle = jobTitle.text?.toString().orEmpty(),
            website = website.text?.toString().orEmpty(),
            note = note.text?.toString().orEmpty(),
            groupName = group.text?.toString().orEmpty(),
            customText = custom.text?.toString().orEmpty(),
        )
    }

    companion object {
        fun build(
            ui: CrmContactDialogUi,
            parent: LinearLayout,
            phone: String,
            titleText: String,
            currentGeneralNote: String,
            savedFields: CallReportStableCrmContactWriter.Fields? = null,
        ): CrmBasicContactInputs {
            val nameInput = ui.input(parent, "Име / показвано име", savedFields?.displayName?.ifBlank { null } ?: titleText.ifBlank { phone })
            ui.input(parent, "Оригинален телефон за свързване", savedFields?.originalPhone?.ifBlank { null } ?: phone).apply {
                isEnabled = false
                setTextColor(Color.rgb(71, 85, 105))
            }
            return CrmBasicContactInputs(
                name = nameInput,
                additionalPhone = ui.input(parent, "Допълнителен телефон", savedFields?.additionalPhone.orEmpty()),
                organization = ui.input(parent, "Организация", savedFields?.organization ?: "Call Report"),
                jobTitle = ui.input(parent, "Тип / длъжност", savedFields?.jobTitle ?: "CRM тест"),
                website = ui.input(parent, "Сайт / линк", savedFields?.website.orEmpty()),
                group = ui.input(parent, "Група", savedFields?.groupName ?: "Call Report CRM"),
                note = ui.input(parent, "Бележка", savedFields?.note ?: currentGeneralNote, lines = 3),
                custom = ui.input(parent, "Custom MIME текст", savedFields?.customText ?: "CRM статус: тест\nПоследна уговорка: ", lines = 3),
            )
        }
    }
}

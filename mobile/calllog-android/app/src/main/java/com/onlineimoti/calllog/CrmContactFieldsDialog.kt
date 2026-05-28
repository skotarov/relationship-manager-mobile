package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object CrmContactFieldsDialog {
    fun show(
        activity: Activity,
        phone: String,
        titleText: String,
        currentGeneralNote: String,
        onSave: (CallReportStableCrmContactWriter.Fields) -> Unit,
    ) {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 4))
        }

        fun input(label: String, value: String = "", lines: Int = 1): EditText {
            root.addView(TextView(activity).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, dp(activity, 8), 0, dp(activity, 3))
            })
            return EditText(activity).apply {
                setText(value)
                textSize = 15f
                minLines = lines
                maxLines = if (lines > 1) 5 else 1
                inputType = InputType.TYPE_CLASS_TEXT or if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                setSingleLine(lines == 1)
                setSelectAllOnFocus(false)
                setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8))
                background = roundedRect(activity, Color.WHITE, 10, Color.rgb(203, 213, 225), 1)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                root.addView(this)
            }
        }

        val nameInput = input("Име / показвано име", titleText.ifBlank { phone })
        input("Оригинален телефон за свързване", phone).apply {
            isEnabled = false
            setTextColor(Color.rgb(71, 85, 105))
        }
        val additionalPhoneInput = input("Допълнителен телефон (тестово)", "")
        val organizationInput = input("Организация", "Call Report")
        val jobTitleInput = input("Тип / длъжност", "CRM тест")
        val websiteInput = input("Сайт / линк", "")
        val groupInput = input("Група", "Call Report CRM")
        val noteInput = input("Бележка", currentGeneralNote, lines = 3)
        val customInput = input("Custom MIME текст", "CRM статус: тест\nПоследна уговорка: ", lines = 3)

        AlertDialog.Builder(activity)
            .setTitle("Добави в CRM")
            .setView(ScrollView(activity).apply { addView(root) })
            .setNegativeButton("Изход", null)
            .setPositiveButton("Запис") { _, _ ->
                onSave(
                    CallReportStableCrmContactWriter.Fields(
                        originalPhone = phone,
                        displayName = nameInput.text?.toString().orEmpty(),
                        additionalPhone = additionalPhoneInput.text?.toString().orEmpty(),
                        organization = organizationInput.text?.toString().orEmpty(),
                        jobTitle = jobTitleInput.text?.toString().orEmpty(),
                        website = websiteInput.text?.toString().orEmpty(),
                        note = noteInput.text?.toString().orEmpty(),
                        groupName = groupInput.text?.toString().orEmpty(),
                        customText = customInput.text?.toString().orEmpty(),
                    )
                )
            }
            .show()
    }

    private fun roundedRect(activity: Activity, color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity, radius).toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(dp(activity, strokeWidth), strokeColor)
        }
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

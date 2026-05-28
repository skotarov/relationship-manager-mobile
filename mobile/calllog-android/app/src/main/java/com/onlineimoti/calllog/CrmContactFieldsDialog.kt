package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

        val modeGroup = RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dp(activity, 8))
        }
        val basicRadio = RadioButton(activity).apply {
            text = "Основни"
            isChecked = true
        }
        val advancedRadio = RadioButton(activity).apply { text = "Разширен" }
        modeGroup.addView(basicRadio)
        modeGroup.addView(advancedRadio)
        root.addView(modeGroup)

        val basicSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val advancedSection = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(basicSection)
        root.addView(advancedSection)

        fun input(parent: LinearLayout, label: String, value: String = "", lines: Int = 1): EditText {
            parent.addView(TextView(activity).apply {
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
                parent.addView(this)
            }
        }

        fun header(parent: LinearLayout, text: String) {
            parent.addView(TextView(activity).apply {
                this.text = text
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(0, dp(activity, 14), 0, dp(activity, 2))
            })
        }

        val nameInput = input(basicSection, "Име / показвано име", titleText.ifBlank { phone })
        input(basicSection, "Оригинален телефон за свързване", phone).apply {
            isEnabled = false
            setTextColor(Color.rgb(71, 85, 105))
        }
        val additionalPhoneInput = input(basicSection, "Допълнителен телефон", "")
        val organizationInput = input(basicSection, "Организация", "Call Report")
        val jobTitleInput = input(basicSection, "Тип / длъжност", "CRM тест")
        val websiteInput = input(basicSection, "Сайт / линк", "")
        val groupInput = input(basicSection, "Група", "Call Report CRM")
        val noteInput = input(basicSection, "Бележка", currentGeneralNote, lines = 3)
        val customInput = input(basicSection, "Custom MIME текст", "CRM статус: тест\nПоследна уговорка: ", lines = 3)

        header(advancedSection, "Име")
        val namePrefixInput = input(advancedSection, "Обръщение / префикс", "")
        val givenNameInput = input(advancedSection, "Собствено име", "")
        val middleNameInput = input(advancedSection, "Презиме", "")
        val familyNameInput = input(advancedSection, "Фамилия", "")
        val nameSuffixInput = input(advancedSection, "Суфикс", "")
        val phoneticNameInput = input(advancedSection, "Фонетично име", "")
        val nicknameInput = input(advancedSection, "Прякор", "")

        header(advancedSection, "Телефони")
        val phoneHomeInput = input(advancedSection, "Домашен телефон", "")
        val phoneWorkInput = input(advancedSection, "Служебен телефон", "")
        val phoneOtherInput = input(advancedSection, "Друг телефон", "")
        val phoneFaxWorkInput = input(advancedSection, "Служебен факс", "")
        val phoneFaxHomeInput = input(advancedSection, "Домашен факс", "")
        val phonePagerInput = input(advancedSection, "Пейджър", "")

        header(advancedSection, "Имейли")
        val emailHomeInput = input(advancedSection, "Личен имейл", "")
        val emailWorkInput = input(advancedSection, "Служебен имейл", "")
        val emailOtherInput = input(advancedSection, "Друг имейл", "")

        header(advancedSection, "Работа / организация")
        val departmentInput = input(advancedSection, "Отдел", "")
        val officeLocationInput = input(advancedSection, "Офис / локация", "")

        header(advancedSection, "Сайтове")
        val websiteHomeInput = input(advancedSection, "Личен сайт", "")
        val websiteBlogInput = input(advancedSection, "Блог", "")
        val websiteProfileInput = input(advancedSection, "Профил", "")

        header(advancedSection, "Домашен адрес")
        val addressHomeStreetInput = input(advancedSection, "Улица / адрес", "")
        val addressHomeCityInput = input(advancedSection, "Град", "")
        val addressHomeRegionInput = input(advancedSection, "Област / регион", "")
        val addressHomePostcodeInput = input(advancedSection, "Пощенски код", "")
        val addressHomeCountryInput = input(advancedSection, "Държава", "")

        header(advancedSection, "Служебен адрес")
        val addressWorkStreetInput = input(advancedSection, "Улица / адрес", "")
        val addressWorkCityInput = input(advancedSection, "Град", "")
        val addressWorkRegionInput = input(advancedSection, "Област / регион", "")
        val addressWorkPostcodeInput = input(advancedSection, "Пощенски код", "")
        val addressWorkCountryInput = input(advancedSection, "Държава", "")

        header(advancedSection, "Дати")
        val birthdayInput = input(advancedSection, "Рожден ден (YYYY-MM-DD)", "")
        val anniversaryInput = input(advancedSection, "Годишнина (YYYY-MM-DD)", "")
        val otherDateInput = input(advancedSection, "Друга дата (YYYY-MM-DD)", "")

        header(advancedSection, "Връзки / комуникация")
        val sipAddressInput = input(advancedSection, "SIP адрес", "")
        val imInput = input(advancedSection, "IM / чат потребител", "")
        val relationSpouseInput = input(advancedSection, "Съпруг/а", "")
        val relationAssistantInput = input(advancedSection, "Асистент", "")
        val relationManagerInput = input(advancedSection, "Мениджър", "")
        val relationReferredByInput = input(advancedSection, "Препоръчан от", "")

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            advancedSection.visibility = if (checkedId == advancedRadio.id) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(activity)
            .setTitle("CRM контакт")
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
                        namePrefix = namePrefixInput.text?.toString().orEmpty(),
                        givenName = givenNameInput.text?.toString().orEmpty(),
                        middleName = middleNameInput.text?.toString().orEmpty(),
                        familyName = familyNameInput.text?.toString().orEmpty(),
                        nameSuffix = nameSuffixInput.text?.toString().orEmpty(),
                        phoneticName = phoneticNameInput.text?.toString().orEmpty(),
                        phoneHome = phoneHomeInput.text?.toString().orEmpty(),
                        phoneWork = phoneWorkInput.text?.toString().orEmpty(),
                        phoneOther = phoneOtherInput.text?.toString().orEmpty(),
                        phoneFaxWork = phoneFaxWorkInput.text?.toString().orEmpty(),
                        phoneFaxHome = phoneFaxHomeInput.text?.toString().orEmpty(),
                        phonePager = phonePagerInput.text?.toString().orEmpty(),
                        emailHome = emailHomeInput.text?.toString().orEmpty(),
                        emailWork = emailWorkInput.text?.toString().orEmpty(),
                        emailOther = emailOtherInput.text?.toString().orEmpty(),
                        department = departmentInput.text?.toString().orEmpty(),
                        officeLocation = officeLocationInput.text?.toString().orEmpty(),
                        websiteHome = websiteHomeInput.text?.toString().orEmpty(),
                        websiteBlog = websiteBlogInput.text?.toString().orEmpty(),
                        websiteProfile = websiteProfileInput.text?.toString().orEmpty(),
                        addressHomeStreet = addressHomeStreetInput.text?.toString().orEmpty(),
                        addressHomeCity = addressHomeCityInput.text?.toString().orEmpty(),
                        addressHomeRegion = addressHomeRegionInput.text?.toString().orEmpty(),
                        addressHomePostcode = addressHomePostcodeInput.text?.toString().orEmpty(),
                        addressHomeCountry = addressHomeCountryInput.text?.toString().orEmpty(),
                        addressWorkStreet = addressWorkStreetInput.text?.toString().orEmpty(),
                        addressWorkCity = addressWorkCityInput.text?.toString().orEmpty(),
                        addressWorkRegion = addressWorkRegionInput.text?.toString().orEmpty(),
                        addressWorkPostcode = addressWorkPostcodeInput.text?.toString().orEmpty(),
                        addressWorkCountry = addressWorkCountryInput.text?.toString().orEmpty(),
                        birthday = birthdayInput.text?.toString().orEmpty(),
                        anniversary = anniversaryInput.text?.toString().orEmpty(),
                        otherDate = otherDateInput.text?.toString().orEmpty(),
                        nickname = nicknameInput.text?.toString().orEmpty(),
                        sipAddress = sipAddressInput.text?.toString().orEmpty(),
                        im = imInput.text?.toString().orEmpty(),
                        relationSpouse = relationSpouseInput.text?.toString().orEmpty(),
                        relationAssistant = relationAssistantInput.text?.toString().orEmpty(),
                        relationManager = relationManagerInput.text?.toString().orEmpty(),
                        relationReferredBy = relationReferredByInput.text?.toString().orEmpty(),
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

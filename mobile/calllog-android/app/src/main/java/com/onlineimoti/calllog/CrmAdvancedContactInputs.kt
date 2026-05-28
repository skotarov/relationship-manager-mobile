package com.onlineimoti.calllog

import android.widget.EditText
import android.widget.LinearLayout

data class CrmAdvancedContactInputs(
    val namePrefix: EditText,
    val givenName: EditText,
    val middleName: EditText,
    val familyName: EditText,
    val nameSuffix: EditText,
    val phoneticName: EditText,
    val nickname: EditText,
    val phoneHome: EditText,
    val phoneWork: EditText,
    val phoneOther: EditText,
    val phoneFaxWork: EditText,
    val phoneFaxHome: EditText,
    val phonePager: EditText,
    val emailHome: EditText,
    val emailWork: EditText,
    val emailOther: EditText,
    val department: EditText,
    val officeLocation: EditText,
    val websiteHome: EditText,
    val websiteBlog: EditText,
    val websiteProfile: EditText,
    val addressHomeStreet: EditText,
    val addressHomeCity: EditText,
    val addressHomeRegion: EditText,
    val addressHomePostcode: EditText,
    val addressHomeCountry: EditText,
    val addressWorkStreet: EditText,
    val addressWorkCity: EditText,
    val addressWorkRegion: EditText,
    val addressWorkPostcode: EditText,
    val addressWorkCountry: EditText,
    val birthday: EditText,
    val anniversary: EditText,
    val otherDate: EditText,
    val sipAddress: EditText,
    val im: EditText,
    val relationSpouse: EditText,
    val relationAssistant: EditText,
    val relationManager: EditText,
    val relationReferredBy: EditText,
) {
    fun applyTo(fields: CallReportStableCrmContactWriter.Fields): CallReportStableCrmContactWriter.Fields {
        return fields.copy(
            namePrefix = namePrefix.text?.toString().orEmpty(),
            givenName = givenName.text?.toString().orEmpty(),
            middleName = middleName.text?.toString().orEmpty(),
            familyName = familyName.text?.toString().orEmpty(),
            nameSuffix = nameSuffix.text?.toString().orEmpty(),
            phoneticName = phoneticName.text?.toString().orEmpty(),
            phoneHome = phoneHome.text?.toString().orEmpty(),
            phoneWork = phoneWork.text?.toString().orEmpty(),
            phoneOther = phoneOther.text?.toString().orEmpty(),
            phoneFaxWork = phoneFaxWork.text?.toString().orEmpty(),
            phoneFaxHome = phoneFaxHome.text?.toString().orEmpty(),
            phonePager = phonePager.text?.toString().orEmpty(),
            emailHome = emailHome.text?.toString().orEmpty(),
            emailWork = emailWork.text?.toString().orEmpty(),
            emailOther = emailOther.text?.toString().orEmpty(),
            department = department.text?.toString().orEmpty(),
            officeLocation = officeLocation.text?.toString().orEmpty(),
            websiteHome = websiteHome.text?.toString().orEmpty(),
            websiteBlog = websiteBlog.text?.toString().orEmpty(),
            websiteProfile = websiteProfile.text?.toString().orEmpty(),
            addressHomeStreet = addressHomeStreet.text?.toString().orEmpty(),
            addressHomeCity = addressHomeCity.text?.toString().orEmpty(),
            addressHomeRegion = addressHomeRegion.text?.toString().orEmpty(),
            addressHomePostcode = addressHomePostcode.text?.toString().orEmpty(),
            addressHomeCountry = addressHomeCountry.text?.toString().orEmpty(),
            addressWorkStreet = addressWorkStreet.text?.toString().orEmpty(),
            addressWorkCity = addressWorkCity.text?.toString().orEmpty(),
            addressWorkRegion = addressWorkRegion.text?.toString().orEmpty(),
            addressWorkPostcode = addressWorkPostcode.text?.toString().orEmpty(),
            addressWorkCountry = addressWorkCountry.text?.toString().orEmpty(),
            birthday = birthday.text?.toString().orEmpty(),
            anniversary = anniversary.text?.toString().orEmpty(),
            otherDate = otherDate.text?.toString().orEmpty(),
            nickname = nickname.text?.toString().orEmpty(),
            sipAddress = sipAddress.text?.toString().orEmpty(),
            im = im.text?.toString().orEmpty(),
            relationSpouse = relationSpouse.text?.toString().orEmpty(),
            relationAssistant = relationAssistant.text?.toString().orEmpty(),
            relationManager = relationManager.text?.toString().orEmpty(),
            relationReferredBy = relationReferredBy.text?.toString().orEmpty(),
        )
    }

    companion object {
        fun build(ui: CrmContactDialogUi, parent: LinearLayout): CrmAdvancedContactInputs {
            ui.header(parent, "Име")
            val namePrefix = ui.input(parent, "Обръщение / префикс", "")
            val givenName = ui.input(parent, "Собствено име", "")
            val middleName = ui.input(parent, "Презиме", "")
            val familyName = ui.input(parent, "Фамилия", "")
            val nameSuffix = ui.input(parent, "Суфикс", "")
            val phoneticName = ui.input(parent, "Фонетично име", "")
            val nickname = ui.input(parent, "Прякор", "")

            ui.header(parent, "Телефони")
            val phoneHome = ui.input(parent, "Домашен телефон", "")
            val phoneWork = ui.input(parent, "Служебен телефон", "")
            val phoneOther = ui.input(parent, "Друг телефон", "")
            val phoneFaxWork = ui.input(parent, "Служебен факс", "")
            val phoneFaxHome = ui.input(parent, "Домашен факс", "")
            val phonePager = ui.input(parent, "Пейджър", "")

            ui.header(parent, "Имейли")
            val emailHome = ui.input(parent, "Личен имейл", "")
            val emailWork = ui.input(parent, "Служебен имейл", "")
            val emailOther = ui.input(parent, "Друг имейл", "")

            ui.header(parent, "Работа / организация")
            val department = ui.input(parent, "Отдел", "")
            val officeLocation = ui.input(parent, "Офис / локация", "")

            ui.header(parent, "Сайтове")
            val websiteHome = ui.input(parent, "Личен сайт", "")
            val websiteBlog = ui.input(parent, "Блог", "")
            val websiteProfile = ui.input(parent, "Профил", "")

            ui.header(parent, "Домашен адрес")
            val addressHomeStreet = ui.input(parent, "Улица / адрес", "")
            val addressHomeCity = ui.input(parent, "Град", "")
            val addressHomeRegion = ui.input(parent, "Област / регион", "")
            val addressHomePostcode = ui.input(parent, "Пощенски код", "")
            val addressHomeCountry = ui.input(parent, "Държава", "")

            ui.header(parent, "Служебен адрес")
            val addressWorkStreet = ui.input(parent, "Улица / адрес", "")
            val addressWorkCity = ui.input(parent, "Град", "")
            val addressWorkRegion = ui.input(parent, "Област / регион", "")
            val addressWorkPostcode = ui.input(parent, "Пощенски код", "")
            val addressWorkCountry = ui.input(parent, "Държава", "")

            ui.header(parent, "Дати")
            val birthday = ui.input(parent, "Рожден ден (YYYY-MM-DD)", "")
            val anniversary = ui.input(parent, "Годишнина (YYYY-MM-DD)", "")
            val otherDate = ui.input(parent, "Друга дата (YYYY-MM-DD)", "")

            ui.header(parent, "Връзки / комуникация")
            val sipAddress = ui.input(parent, "SIP адрес", "")
            val im = ui.input(parent, "IM / чат потребител", "")
            val relationSpouse = ui.input(parent, "Съпруг/а", "")
            val relationAssistant = ui.input(parent, "Асистент", "")
            val relationManager = ui.input(parent, "Мениджър", "")
            val relationReferredBy = ui.input(parent, "Препоръчан от", "")

            return CrmAdvancedContactInputs(
                namePrefix = namePrefix,
                givenName = givenName,
                middleName = middleName,
                familyName = familyName,
                nameSuffix = nameSuffix,
                phoneticName = phoneticName,
                nickname = nickname,
                phoneHome = phoneHome,
                phoneWork = phoneWork,
                phoneOther = phoneOther,
                phoneFaxWork = phoneFaxWork,
                phoneFaxHome = phoneFaxHome,
                phonePager = phonePager,
                emailHome = emailHome,
                emailWork = emailWork,
                emailOther = emailOther,
                department = department,
                officeLocation = officeLocation,
                websiteHome = websiteHome,
                websiteBlog = websiteBlog,
                websiteProfile = websiteProfile,
                addressHomeStreet = addressHomeStreet,
                addressHomeCity = addressHomeCity,
                addressHomeRegion = addressHomeRegion,
                addressHomePostcode = addressHomePostcode,
                addressHomeCountry = addressHomeCountry,
                addressWorkStreet = addressWorkStreet,
                addressWorkCity = addressWorkCity,
                addressWorkRegion = addressWorkRegion,
                addressWorkPostcode = addressWorkPostcode,
                addressWorkCountry = addressWorkCountry,
                birthday = birthday,
                anniversary = anniversary,
                otherDate = otherDate,
                sipAddress = sipAddress,
                im = im,
                relationSpouse = relationSpouse,
                relationAssistant = relationAssistant,
                relationManager = relationManager,
                relationReferredBy = relationReferredBy,
            )
        }
    }
}

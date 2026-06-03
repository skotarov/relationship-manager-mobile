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
        fun build(ui: CrmContactDialogUi, parent: LinearLayout, savedFields: CallReportStableCrmContactWriter.Fields? = null): CrmAdvancedContactInputs {
            ui.header(parent, "Име")
            val namePrefix = ui.input(parent, "Обръщение / префикс", savedFields?.namePrefix.orEmpty())
            val givenName = ui.input(parent, "Собствено име", savedFields?.givenName.orEmpty())
            val middleName = ui.input(parent, "Презиме", savedFields?.middleName.orEmpty())
            val familyName = ui.input(parent, "Фамилия", savedFields?.familyName.orEmpty())
            val nameSuffix = ui.input(parent, "Суфикс", savedFields?.nameSuffix.orEmpty())
            val phoneticName = ui.input(parent, "Фонетично име", savedFields?.phoneticName.orEmpty())
            val nickname = ui.input(parent, "Прякор", savedFields?.nickname.orEmpty())

            ui.header(parent, "Телефони")
            val phoneHome = ui.input(parent, "Домашен телефон", savedFields?.phoneHome.orEmpty())
            val phoneWork = ui.input(parent, "Служебен телефон", savedFields?.phoneWork.orEmpty())
            val phoneOther = ui.input(parent, "Друг телефон", savedFields?.phoneOther.orEmpty())
            val phoneFaxWork = ui.input(parent, "Служебен факс", savedFields?.phoneFaxWork.orEmpty())
            val phoneFaxHome = ui.input(parent, "Домашен факс", savedFields?.phoneFaxHome.orEmpty())
            val phonePager = ui.input(parent, "Пейджър", savedFields?.phonePager.orEmpty())

            ui.header(parent, "Имейли")
            val emailHome = ui.input(parent, "Личен имейл", savedFields?.emailHome.orEmpty())
            val emailWork = ui.input(parent, "Служебен имейл", savedFields?.emailWork.orEmpty())
            val emailOther = ui.input(parent, "Друг имейл", savedFields?.emailOther.orEmpty())

            ui.header(parent, "Работа / организация")
            val department = ui.input(parent, "Отдел", savedFields?.department.orEmpty())
            val officeLocation = ui.input(parent, "Офис / локация", savedFields?.officeLocation.orEmpty())

            ui.header(parent, "Сайтове")
            val websiteHome = ui.input(parent, "Личен сайт", savedFields?.websiteHome.orEmpty())
            val websiteBlog = ui.input(parent, "Блог", savedFields?.websiteBlog.orEmpty())
            val websiteProfile = ui.input(parent, "Профил", savedFields?.websiteProfile.orEmpty())

            ui.header(parent, "Домашен адрес")
            val addressHomeStreet = ui.input(parent, "Улица / адрес", savedFields?.addressHomeStreet.orEmpty())
            val addressHomeCity = ui.input(parent, "Град", savedFields?.addressHomeCity.orEmpty())
            val addressHomeRegion = ui.input(parent, "Област / регион", savedFields?.addressHomeRegion.orEmpty())
            val addressHomePostcode = ui.input(parent, "Пощенски код", savedFields?.addressHomePostcode.orEmpty())
            val addressHomeCountry = ui.input(parent, "Държава", savedFields?.addressHomeCountry.orEmpty())

            ui.header(parent, "Служебен адрес")
            val addressWorkStreet = ui.input(parent, "Улица / адрес", savedFields?.addressWorkStreet.orEmpty())
            val addressWorkCity = ui.input(parent, "Град", savedFields?.addressWorkCity.orEmpty())
            val addressWorkRegion = ui.input(parent, "Област / регион", savedFields?.addressWorkRegion.orEmpty())
            val addressWorkPostcode = ui.input(parent, "Пощенски код", savedFields?.addressWorkPostcode.orEmpty())
            val addressWorkCountry = ui.input(parent, "Държава", savedFields?.addressWorkCountry.orEmpty())

            ui.header(parent, "Дати")
            val birthday = ui.input(parent, "Рожден ден (YYYY-MM-DD)", savedFields?.birthday.orEmpty())
            val anniversary = ui.input(parent, "Годишнина (YYYY-MM-DD)", savedFields?.anniversary.orEmpty())
            val otherDate = ui.input(parent, "Друга дата (YYYY-MM-DD)", savedFields?.otherDate.orEmpty())

            ui.header(parent, "Връзки / комуникация")
            val sipAddress = ui.input(parent, "SIP адрес", savedFields?.sipAddress.orEmpty())
            val im = ui.input(parent, "IM / чат потребител", savedFields?.im.orEmpty())
            val relationSpouse = ui.input(parent, "Съпруг/а", savedFields?.relationSpouse.orEmpty())
            val relationAssistant = ui.input(parent, "Асистент", savedFields?.relationAssistant.orEmpty())
            val relationManager = ui.input(parent, "Мениджър", savedFields?.relationManager.orEmpty())
            val relationReferredBy = ui.input(parent, "Препоръчан от", savedFields?.relationReferredBy.orEmpty())

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

package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class CrmContactHistoryResult(
    val company: CrmCompanyInfo = CrmCompanyInfo(),
    val authenticatedUser: CrmAuthenticatedUser = CrmAuthenticatedUser(),
    val crmContact: CrmContactInfo = CrmContactInfo(),
    val serverNotes: List<CrmServerNote> = emptyList(),
)

data class CrmCompanyInfo(
    val id: String = "",
    val code: String = "",
    val name: String = "",
)

data class CrmAuthenticatedUser(
    val employeeId: String = "",
    val login: String = "",
    val name: String = "",
)

data class CrmContactInfo(
    val exists: Boolean = false,
    val id: String = "",
    val name: String = "",
    val syncEnabled: Boolean = false,
)

data class CrmServerNote(
    val serverNoteId: String = "",
    val clientNoteId: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val authorId: String = "",
    val authorLogin: String = "",
    val authorName: String = "",
    val text: String = "",
    val source: String = "crm",
    val propertyId: String = "",
    val propertyTitle: String = "",
)

internal object CrmContactHistoryClient {
    private const val DEFAULT_CONTACT_HISTORY_PATH = "/crm/api/v1/contact_history.php"
    private const val DEFAULT_LIMIT = 50

    fun fetch(config: AppConfig, phone: String): CrmContactHistoryResult {
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank() || phone.isBlank()) {
            return CrmContactHistoryResult()
        }

        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = DEFAULT_CONTACT_HISTORY_PATH,
            params = linkedMapOf(
                "phone" to phone,
                "limit" to DEFAULT_LIMIT.toString(),
                "access_token" to config.accessToken,
                "security_code" to config.accessToken,
            )
        )
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("X-Callreport-Token", config.accessToken)
        connection.setRequestProperty("X-CRM-Security-Code", config.accessToken)

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input -> BufferedReader(InputStreamReader(input)).readText() }
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: $body")

        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "CRM history error"))
        return parseResult(json)
    }

    private fun parseResult(json: JSONObject): CrmContactHistoryResult {
        val notesJson = json.optJSONArray("server_notes")
        val notes = buildList {
            if (notesJson != null) {
                for (index in 0 until notesJson.length()) {
                    val item = notesJson.optJSONObject(index) ?: continue
                    val note = CrmServerNote(
                        serverNoteId = item.optString("server_note_id"),
                        clientNoteId = item.optString("client_note_id"),
                        createdAt = item.optString("created_at"),
                        updatedAt = item.optString("updated_at"),
                        authorId = item.optString("author_id"),
                        authorLogin = item.optString("author_login"),
                        authorName = item.optString("author_name"),
                        text = item.optString("text"),
                        source = item.optString("source", "crm"),
                        propertyId = item.optString("property_id"),
                        propertyTitle = item.optString("property_title"),
                    )
                    if (note.text.isNotBlank()) add(note)
                }
            }
        }

        val companyJson = json.optJSONObject("company")
        val userJson = json.optJSONObject("authenticated_user")
        val contactJson = json.optJSONObject("crm_contact")
        return CrmContactHistoryResult(
            company = CrmCompanyInfo(
                id = companyJson?.optString("id").orEmpty(),
                code = companyJson?.optString("code").orEmpty(),
                name = companyJson?.optString("name").orEmpty(),
            ),
            authenticatedUser = CrmAuthenticatedUser(
                employeeId = userJson?.optString("employee_id").orEmpty(),
                login = userJson?.optString("login").orEmpty(),
                name = userJson?.optString("name").orEmpty(),
            ),
            crmContact = CrmContactInfo(
                exists = contactJson?.optBoolean("exists", false) ?: false,
                id = contactJson?.optString("id").orEmpty(),
                name = contactJson?.optString("name").orEmpty(),
                syncEnabled = contactJson?.optBoolean("sync_enabled", false) ?: false,
            ),
            serverNotes = notes,
        )
    }
}

package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Reads the authenticated user's CRM contacts from the Relationship Manager server. */
internal object ServerCrmContactsClient {
    private const val PATH = "/relationship-manager/contacts_lookup.php"

    fun lookup(config: AppConfig): List<PhoneCallRecord> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()
        val endpoint = config.baseUrl.trim().trimEnd('/') + PATH
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Contacts lookup failed"))
            val contacts = json.optJSONArray("contacts")
            return buildList {
                for (index in 0 until (contacts?.length() ?: 0)) {
                    val item = contacts?.optJSONObject(index) ?: continue
                    val phone = item.optString("phone").trim()
                    if (HomeCallPageLoader.noteKey(phone).isBlank()) continue
                    add(
                        PhoneCallRecord(
                            number = phone,
                            name = item.optString("contact_name").trim(),
                            direction = "",
                            startedAt = item.optLong("last_activity_at_ms", 0L).coerceAtLeast(0L),
                            durationSeconds = 0L,
                        ),
                    )
                }
            }.distinctBy { HomeCallPageLoader.noteKey(it.number) }
        } finally {
            connection.disconnect()
        }
    }
}

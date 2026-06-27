package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportCompanyMainNote(
    val companyId: String,
    val companyName: String,
    val note: String,
    val updatedAtMs: Long,
    val confirmedByServer: Boolean,
    val pending: Boolean,
)

internal object CallReportCompanyGeneralNotesClient {
    private const val PATH = "/broker/callreport/history_lookup.php"

    fun fetch(context: android.content.Context, config: AppConfig, phone: String): List<CallReportCompanyMainNote> {
        if (!CallReportRemoteAccess.isReady(config) || phone.isBlank()) return emptyList()
        val connection = URL(buildEndpoint(config.baseUrl, PATH, linkedMapOf("phone" to phone, "limit" to "200")))
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val response = JSONObject(body)
            if (!response.optBoolean("ok", false)) throw IllegalStateException(response.optString("error", "History lookup failed"))
            return parse(context, phone, response)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(context: android.content.Context, phone: String, response: JSONObject): List<CallReportCompanyMainNote> {
        val companies = linkedMapOf<String, String>()
        val principal = response.optJSONObject("principal")
        principal?.optJSONArray("companies")?.let { source ->
            for (index in 0 until source.length()) {
                val company = source.optJSONObject(index) ?: continue
                val id = company.optString("id").trim()
                if (id.isNotBlank()) companies[id] = company.optString("name").trim().ifBlank { id }
            }
        }

        val latestByCompany = mutableMapOf<String, RemoteNote>()
        val phoneKey = phoneKey(phone)
        val items = response.optJSONArray("history_items") ?: response.optJSONArray("items")
        if (items != null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val clientId = item.optString("client_event_id").trim()
                val isGeneralNote = clientId.contains(":topic:general:") ||
                    clientId.contains(":note:general:") ||
                    (item.optString("communication_type").equals("note", ignoreCase = true) &&
                        item.optString("direction").trim().isBlank() &&
                        item.optLong("duration_seconds", item.optLong("duration", 0L)) <= 0L)
                if (!isGeneralNote) continue
                if (phoneKey(item.optString("phone")) != phoneKey) continue

                val companyId = item.optString("company_id").trim()
                if (companyId.isBlank()) continue
                val companyName = item.optString("company_name").trim().ifBlank { companies[companyId].orEmpty().ifBlank { companyId } }
                companies.putIfAbsent(companyId, companyName)
                val updatedAt = item.optLong("updated_at_ms", item.optLong("occurred_at_ms", 0L))
                val candidate = RemoteNote(item.optString("note").trim(), updatedAt)
                val current = latestByCompany[companyId]
                if (current == null || candidate.updatedAtMs >= current.updatedAtMs) latestByCompany[companyId] = candidate
            }
        }

        return companies.entries
            .map { (companyId, companyName) ->
                val remote = latestByCompany[companyId]
                val cached = CallReportCompanyGeneralNoteStore.noteFor(context, phone, companyId)
                val pending = CallReportCompanyGeneralNotePending.isPending(context, phone, companyId)
                val note = when {
                    pending && cached.isNotBlank() -> cached
                    remote != null -> remote.note
                    else -> cached
                }
                CallReportCompanyMainNote(
                    companyId = companyId,
                    companyName = companyName,
                    note = note,
                    updatedAtMs = remote?.updatedAtMs ?: 0L,
                    confirmedByServer = remote != null && !pending && remote.note.isNotBlank(),
                    pending = pending,
                )
            }
            .sortedBy { it.companyName.lowercase() }
    }

    private data class RemoteNote(val note: String, val updatedAtMs: Long)

    private fun phoneKey(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }
}

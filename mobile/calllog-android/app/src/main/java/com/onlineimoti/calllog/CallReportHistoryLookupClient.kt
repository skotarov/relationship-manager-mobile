package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportHistoryPrincipal(
    val brokerId: String = "",
    val brokerName: String = "",
)

internal data class CallReportHistoryEvent(
    val serverId: String = "",
    val clientEventId: String = "",
    val communicationType: String = "phone",
    val phone: String = "",
    val direction: String = "",
    val status: String = "",
    val occurredAtMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val note: String = "",
    val contactName: String = "",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val authorBrokerId: String = "",
    val authorBrokerName: String = "",
)

internal data class CallReportHistoryLookupResult(
    val principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    val events: List<CallReportHistoryEvent> = emptyList(),
)

internal object CallReportHistoryLookupClient {
    private const val PATH = "/broker/callreport/history_lookup.php"

    fun lookup(config: AppConfig, phone: String): CallReportHistoryLookupResult {
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank() || phone.isBlank()) {
            return CallReportHistoryLookupResult()
        }
        val url = buildEndpoint(config.baseUrl, PATH, linkedMapOf("phone" to phone, "limit" to "200"))
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "History lookup failed"))
            return parse(json)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): CallReportHistoryLookupResult {
        val principalJson = json.optJSONObject("principal") ?: json.optJSONObject("authenticated_principal")
        val principal = CallReportHistoryPrincipal(
            brokerId = principalJson?.optString("broker_id").orEmpty().trim(),
            brokerName = principalJson?.optString("broker_name").orEmpty().trim(),
        )
        val events = buildList {
            val items = json.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val event = CallReportHistoryEvent(
                        serverId = item.text("id", "server_id"),
                        clientEventId = item.text("client_event_id"),
                        communicationType = item.text("communication_type", "type").ifBlank { "phone" },
                        phone = item.text("phone", "number"),
                        direction = item.text("direction"),
                        status = item.text("status"),
                        occurredAtMs = item.number("occurred_at_ms", "timestamp", "date"),
                        durationSeconds = item.number("duration_seconds", "duration"),
                        note = item.text("note", "notes", "text"),
                        contactName = item.text("contact_name", "contact"),
                        createdAtMs = item.number("created_at_ms", "created_at"),
                        updatedAtMs = item.number("updated_at_ms", "updated_at"),
                        authorBrokerId = item.text("author_broker_id", "created_by_broker_id", "note_author_broker_id"),
                        authorBrokerName = item.text("author_broker_name", "created_by_broker_name", "note_author_broker_name", "author"),
                    )
                    if (event.phone.isNotBlank() && event.occurredAtMs > 0L) add(event)
                }
            }
        }
        return CallReportHistoryLookupResult(principal, events)
    }

    private fun JSONObject.text(vararg keys: String): String {
        keys.forEach { key ->
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun JSONObject.number(vararg keys: String): Long {
        keys.forEach { key ->
            val value = opt(key)
            when (value) {
                is Number -> if (value.toLong() > 0L) return value.toLong()
                is String -> value.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
            }
        }
        return 0L
    }
}

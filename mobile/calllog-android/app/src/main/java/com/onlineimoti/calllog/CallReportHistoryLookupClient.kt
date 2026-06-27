package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

internal data class CallReportHistoryCompany(
    val id: String,
    val name: String,
)

internal data class CallReportHistoryPrincipal(
    val brokerId: String = "",
    val brokerName: String = "",
    val companies: List<CallReportHistoryCompany> = emptyList(),
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
    val companyId: String = "",
)

internal data class CallReportHistoryLookupResult(
    val principal: CallReportHistoryPrincipal = CallReportHistoryPrincipal(),
    val events: List<CallReportHistoryEvent> = emptyList(),
)

internal object CallReportHistoryLookupClient {
    private const val PATH = "/broker/callreport/history_lookup.php"
    private val generalNoteServerPhones = ConcurrentHashMap.newKeySet<String>()

    fun lookup(config: AppConfig, phone: String): CallReportHistoryLookupResult {
        if (!isReady(config) || phone.isBlank()) return CallReportHistoryLookupResult()
        return request(config, listOf(phone)).also { result ->
            updateGeneralNoteServerPresence(phone, result.events)
        }
    }

    /** One request for up to 50 phones; used by Home to avoid serial per-row lookups. */
    fun lookupMany(config: AppConfig, phones: List<String>): CallReportHistoryLookupResult {
        if (!isReady(config)) return CallReportHistoryLookupResult()
        val requestedPhones = phones
            .map(String::trim)
            .filter { phoneKey(it).isNotBlank() }
            .distinctBy(::phoneKey)
            .take(50)
        if (requestedPhones.isEmpty()) return CallReportHistoryLookupResult()
        return request(config, requestedPhones)
    }

    /** Server presence of the main contact note, independent of this installation's client_event_id. */
    fun hasGeneralNoteOnServer(phone: String): Boolean {
        val key = phoneKey(phone)
        return key.isNotBlank() && key in generalNoteServerPhones
    }

    /** Called after a successful durable general-note sync, before a subsequent history refresh arrives. */
    fun markGeneralNoteOnServer(phone: String) {
        phoneKey(phone).takeIf { it.isNotBlank() }?.let { key ->
            generalNoteServerPhones.add(key)
        }
    }

    private fun request(config: AppConfig, phones: List<String>): CallReportHistoryLookupResult {
        val singlePhone = phones.singleOrNull()
        val url = if (singlePhone != null) {
            buildEndpoint(config.baseUrl, PATH, linkedMapOf("phone" to singlePhone, "limit" to "200"))
        } else {
            buildEndpoint(config.baseUrl, PATH, linkedMapOf("limit" to "200"))
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = if (singlePhone != null) "GET" else "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            if (singlePhone == null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = JSONObject().apply {
                    put("phones", JSONArray().apply { phones.forEach(::put) })
                }.toString()
                connection.outputStream.use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                }
            }
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

    private fun isReady(config: AppConfig): Boolean =
        config.remoteEnabled && config.baseUrl.isNotBlank() && config.accessToken.isNotBlank()

    private fun updateGeneralNoteServerPresence(phone: String, events: List<CallReportHistoryEvent>) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        if (events.any { event -> isGeneralNoteEvent(event, key) }) {
            generalNoteServerPhones.add(key)
        } else {
            generalNoteServerPhones.remove(key)
        }
    }

    private fun isGeneralNoteEvent(event: CallReportHistoryEvent, requestedPhoneKey: String): Boolean {
        if (phoneKey(event.phone) != requestedPhoneKey) return false
        return event.clientEventId.contains(":note:general:") ||
            event.clientEventId.contains(":topic:general:") ||
            (event.communicationType.equals("note", ignoreCase = true) &&
                event.direction.isBlank() && event.durationSeconds <= 0L)
    }

    private fun phoneKey(phone: String): String = HomeCallPageLoader.noteKey(phone)

    private fun parse(json: JSONObject): CallReportHistoryLookupResult {
        val principalJson = json.optJSONObject("principal") ?: json.optJSONObject("authenticated_principal")
        val companies = buildList {
            val source = principalJson?.optJSONArray("companies")
            if (source != null) {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    add(CallReportHistoryCompany(id, item.optString("name").trim().ifBlank { id }))
                }
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        val principal = CallReportHistoryPrincipal(
            brokerId = principalJson?.optString("broker_id").orEmpty().trim(),
            brokerName = principalJson?.optString("broker_name").orEmpty().trim(),
            companies = companies,
        )
        val events = buildList {
            val items = json.optJSONArray("history_items") ?: json.optJSONArray("items")
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
                        companyId = item.text("company_id"),
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

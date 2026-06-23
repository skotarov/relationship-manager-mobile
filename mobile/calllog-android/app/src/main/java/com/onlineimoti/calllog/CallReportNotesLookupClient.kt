package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Batch client for server notes shown below the local Home call rows. */
internal data class CallReportServerNote(
    val displayName: String,
    val lastNote: String,
    val lastNoteAtMs: Long,
    val authorBrokerName: String,
    val callCount: Int,
    val smsCount: Int,
)

internal object CallReportNotesLookupClient {
    private const val NOTES_LOOKUP_PATH = "/broker/callreport/notes_lookup.php"
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 7_000
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    private data class CachedNote(val value: CallReportServerNote, val expiresAtMs: Long)
    private val cacheLock = Any()
    private val cache = linkedMapOf<String, CachedNote>()

    fun lookup(config: AppConfig, phones: List<String>): Map<String, CallReportServerNote> {
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return emptyMap()
        val requested = phones
            .map { value -> value.trim() }
            .filter { value -> value.isNotBlank() }
            .distinctBy { value -> HomeCallPageLoader.noteKey(value) }
            .take(50)
        if (requested.isEmpty()) return emptyMap()

        val now = System.currentTimeMillis()
        val cached = linkedMapOf<String, CallReportServerNote>()
        val missing = mutableListOf<String>()
        synchronized(cacheLock) {
            requested.forEach { phone ->
                val key = HomeCallPageLoader.noteKey(phone)
                val entry = cache[key]
                if (entry != null && entry.expiresAtMs > now) {
                    cached[key] = entry.value
                } else {
                    if (entry != null) cache.remove(key)
                    missing += phone
                }
            }
        }
        if (missing.isEmpty()) return cached

        val fresh = request(config, missing)
        synchronized(cacheLock) {
            fresh.forEach { (key, note) ->
                cache[key] = CachedNote(note, now + CACHE_TTL_MS)
            }
        }
        return cached + fresh
    }

    private fun request(config: AppConfig, phones: List<String>): Map<String, CallReportServerNote> {
        val endpoint = config.baseUrl.trim().trimEnd('/') + NOTES_LOOKUP_PATH
        val requestBody = JSONObject().apply {
            put("phones", JSONArray(phones))
        }.toString()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { output -> output.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            if (responseCode !in 200..299) throw IOException("Notes lookup failed: HTTP $responseCode")

            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) throw IOException("Notes lookup returned an invalid response")
            val items = json.optJSONArray("items") ?: return emptyMap()
            return buildMap {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val requestedPhone = item.optString("requested_phone")
                    val normalizedPhone = item.optString("normalized_phone")
                    val key = HomeCallPageLoader.noteKey(requestedPhone.ifBlank { normalizedPhone })
                    if (key.isBlank()) continue
                    put(
                        key,
                        CallReportServerNote(
                            displayName = item.optString("display_name").trim(),
                            lastNote = item.optString("last_note").trim(),
                            lastNoteAtMs = item.optLong("last_note_at_ms", 0L),
                            authorBrokerName = item.optString("author_broker_name").trim(),
                            callCount = item.optInt("call_count", 0),
                            smsCount = item.optInt("sms_count", 0),
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

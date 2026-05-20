package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ServerHistoryRepository {
    private val cache = linkedMapOf<String, ServerHistorySnippet>()
    private var cacheSignature: String = ""

    @Synchronized
    fun clearIfConfigChanged(config: AppConfig) {
        val signature = signature(config)
        if (cacheSignature == signature) {
            return
        }
        cache.clear()
        cacheSignature = signature
    }

    fun fetchLatestNotes(config: AppConfig, phones: List<String>): Map<String, ServerHistorySnippet> {
        clearIfConfigChanged(config)

        val uniqueLastDigits = phones
            .map { phoneLastDigits(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (uniqueLastDigits.isEmpty() || config.baseUrl.isBlank()) {
            return emptyMap()
        }

        val cachedItems = synchronized(this) {
            uniqueLastDigits.mapNotNull { lastDigits ->
                cache[lastDigits]?.let { lastDigits to it }
            }.toMap()
        }
        val missingPhones = phones
            .distinct()
            .filter { phoneLastDigits(it).isNotBlank() && !cachedItems.containsKey(phoneLastDigits(it)) }
        if (missingPhones.isEmpty()) {
            return cachedItems
        }

        val fetched = requestLatestNotes(config, missingPhones)
        synchronized(this) {
            fetched.forEach { (lastDigits, snippet) ->
                cache[lastDigits] = snippet
            }
        }

        return synchronized(this) {
            uniqueLastDigits.mapNotNull { lastDigits ->
                cache[lastDigits]?.let { lastDigits to it }
            }.toMap()
        }
    }

    private fun requestLatestNotes(config: AppConfig, phones: List<String>): Map<String, ServerHistorySnippet> {
        val url = config.buildHistoryLookupUrl(phones)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        if (config.accessToken.isNotBlank()) {
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $body")
        }

        val root = JSONObject(body)
        val itemsJson = root.optJSONArray("items") ?: return emptyMap()
        val result = linkedMapOf<String, ServerHistorySnippet>()
        for (index in 0 until itemsJson.length()) {
            val item = itemsJson.optJSONObject(index) ?: continue
            val lastDigits = item.optString("last_digits").trim()
            if (lastDigits.isBlank()) {
                continue
            }
            result[lastDigits] = ServerHistorySnippet(
                lastDigits = lastDigits,
                latestNote = item.optString("latest_note").trim(),
                latestNoteAt = item.optString("latest_note_at").trim(),
                latestNoteTimestampMs = item.optLong("latest_note_timestamp", 0L),
                latestNoteDirection = item.optString("latest_note_direction").trim(),
                latestNoteContact = item.optString("latest_note_contact").trim(),
                latestNotePropertyId = item.optString("latest_note_property_id").trim(),
                latestNotePropertyTitle = item.optString("latest_note_property_title").trim(),
                latestContact = item.optString("latest_contact").trim(),
                latestPropertyTitle = item.optString("latest_property_title").trim(),
                entryCount = item.optInt("entry_count", 0),
            )
        }
        return result
    }

    private fun signature(config: AppConfig): String {
        return "${config.baseUrl.trim()}|${config.accessToken.trim()}"
    }
}

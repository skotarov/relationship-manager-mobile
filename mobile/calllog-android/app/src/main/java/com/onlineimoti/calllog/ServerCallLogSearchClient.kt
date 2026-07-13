package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Searches the authenticated Relationship Manager server account for the main Call Log search box. */
internal object ServerCallLogSearchClient {
    private const val PATH = "/relationship-manager/search_lookup.php"

    fun search(
        config: AppConfig,
        query: String,
        context: Context? = null,
    ): List<PhoneCallRecord> {
        val trimmedQuery = query.trim()
        if (!CallReportRemoteAccess.isReady(config) || trimmedQuery.isBlank()) return emptyList()
        val endpoint = buildEndpoint(
            config.baseUrl,
            PATH,
            linkedMapOf(
                "access_token" to config.accessToken,
                "q" to trimmedQuery,
                "search" to trimmedQuery,
                "limit" to "500",
            ),
        )
        val connection = runCatching { URL(endpoint).openConnection() as HttpURLConnection }.getOrElse { error ->
            ServerConnectionNotifier.notifyFailure(context, config, error)
            throw error
        }
        try {
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
                if (!json.optBoolean("ok", false)) throw IllegalStateException(json.optString("error", "Search lookup failed"))
                val contacts = json.optJSONArray("contacts") ?: json.optJSONArray("items")
                return buildList {
                    for (index in 0 until (contacts?.length() ?: 0)) {
                        val item = contacts?.optJSONObject(index) ?: continue
                        val phone = item.optString("phone").trim().ifBlank { item.optString("number").trim() }
                        if (HomeCallPageLoader.noteKey(phone).isBlank()) continue
                        add(
                            PhoneCallRecord(
                                number = phone,
                                name = item.optString("local_contact_name").trim()
                                    .ifBlank { item.optString("contact_name").trim() }
                                    .ifBlank { item.optString("name").trim() },
                                direction = "",
                                startedAt = item.optLong("last_activity_at_ms", 0L)
                                    .coerceAtLeast(item.optLong("updated_at_ms", 0L))
                                    .coerceAtLeast(item.optLong("created_at_ms", 0L)),
                                durationSeconds = 0L,
                                providerId = "server-search:${HomeCallPageLoader.noteKey(phone)}:${index}",
                                searchSnippet = item.optString("search_match_text").trim()
                                    .ifBlank { item.optString("search_snippet").trim() }
                                    .ifBlank { item.optString("matched_note").trim() }
                                    .ifBlank { item.optString("matched_text").trim() },
                            ),
                        )
                    }
                }.distinctBy { HomeCallPageLoader.noteKey(it.number) }
            } catch (error: Throwable) {
                ServerConnectionNotifier.notifyFailure(context, config, error)
                throw error
            }
        } finally {
            connection.disconnect()
        }
    }
}

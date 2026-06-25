package com.onlineimoti.calllog

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal data class CallReportTopicCompany(
    val id: String,
    val name: String,
)

internal object CallReportTopicCompaniesClient {
    private const val PATH = "/broker/callreport/topic_companies.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    fun fetch(config: AppConfig): List<CallReportTopicCompany> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyList()
        val endpoint = config.baseUrl.trim().trimEnd('/') + PATH
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val response = runCatching { JSONObject(body) }.getOrNull()
            if (code !in 200..299 || response?.optBoolean("ok", false) != true) {
                throw IOException("Topic companies request was rejected.")
            }

            val companies = response.optJSONArray("companies") ?: return emptyList()
            return buildList {
                for (index in 0 until companies.length()) {
                    val item = companies.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    if (id.isNotBlank() && name.isNotBlank()) add(CallReportTopicCompany(id, name))
                }
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        } finally {
            connection.disconnect()
        }
    }
}

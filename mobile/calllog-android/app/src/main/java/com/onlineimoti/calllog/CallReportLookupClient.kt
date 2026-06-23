package com.onlineimoti.calllog

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal object CallReportLookupClient {
    private const val HISTORY_LIMIT = 5

    fun fetchLookup(
        config: AppConfig,
        phone: String,
        direction: String,
        context: CallReportLookupContext = CallReportLookupContext(),
    ): LookupResult {
        val params = linkedMapOf(
            "phone" to phone,
            "direction" to direction,
            "history_limit" to HISTORY_LIMIT.toString(),
            "access_token" to config.accessToken,
        )
        params.putAll(context.asQueryParameters())
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = config.lookupPath,
            params = params,
        )
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        if (config.accessToken.isNotBlank()) connection.setRequestProperty("X-Callreport-Token", config.accessToken)

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input -> BufferedReader(InputStreamReader(input)).readText() }
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode: $body")

        val json = org.json.JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val serverLines = buildList {
            if (linesJson != null) for (index in 0 until linesJson.length()) add(linesJson.optString(index))
        }
        val previousCallCount = json.optInt("previous_call_count", -1)
        val previousSmsCount = json.optInt("previous_sms_count", -1)
        val recentLinesJson = json.optJSONArray("recent_call_lines")
        val recentLines = buildList {
            if (recentLinesJson != null) for (index in 0 until recentLinesJson.length()) add(recentLinesJson.optString(index))
        }
        val lines = buildList {
            if (previousCallCount >= 0) add("В Call Report: $previousCallCount разговора")
            if (previousSmsCount > 0) add("SMS: $previousSmsCount")
            addAll(serverLines)
            addAll(recentLines.take(HISTORY_LIMIT))
        }
        val openFormUrl = json.optString("open_form_url")
        val resolvedFormUrl = if (openFormUrl.startsWith("http")) openFormUrl else config.baseUrl.trim().trimEnd('/') + openFormUrl
        return LookupResult(
            title = json.optString("title", phone),
            subtitle = json.optString("subtitle", ""),
            lines = lines,
            openFormUrl = resolvedFormUrl,
        )
    }
}

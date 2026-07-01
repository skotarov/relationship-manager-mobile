package com.onlineimoti.calllog

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Resolves the active negotiation phases for the CRM overview in batches. Phase is
 * company-scoped, so a phone matches when it has the selected phase in any firm
 * available to the current broker.
 */
internal object HomeCrmPhaseLookup {
    private const val MAX_PHONES_PER_REQUEST = 50
    private const val MAX_PARALLEL_REQUESTS = 4
    private const val MAX_CACHE_ENTRIES = 5_000
    private val phaseCache = ConcurrentHashMap<String, Set<Int>>()

    /**
     * Missing or failed batches deliberately stay unresolved and therefore cannot
     * match a selected phase. This prevents a network gap from showing leads under
     * an incorrect phase.
     */
    fun resolve(config: AppConfig, phones: List<String>): Map<String, Set<Int>> {
        if (!CallReportRemoteAccess.isReady(config)) return emptyMap()
        val phoneByKey = linkedMapOf<String, String>()
        phones.forEach { phone ->
            val key = HomeCallPageLoader.noteKey(phone)
            if (key.isNotBlank()) phoneByKey.putIfAbsent(key, phone)
        }
        if (phoneByKey.isEmpty()) return emptyMap()

        val scope = accountScope(config)
        val missingPhones = phoneByKey
            .filterKeys { phaseCache[cacheKey(scope, it)] == null }
            .values
            .toList()
        if (missingPhones.isNotEmpty()) fetchMissing(scope, config, missingPhones)

        return phoneByKey.keys.mapNotNull { phoneKey ->
            phaseCache[cacheKey(scope, phoneKey)]?.let { phases -> phoneKey to phases }
        }.toMap()
    }

    private fun fetchMissing(scope: String, config: AppConfig, phones: List<String>) {
        if (phaseCache.size > MAX_CACHE_ENTRIES) phaseCache.clear()
        val batches = phones.chunked(MAX_PHONES_PER_REQUEST)
        val executor = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_REQUESTS, batches.size))
        try {
            val futures = batches.map { batch ->
                executor.submit(Callable { CompanyNegotiationPhaseBatchRemoteClient.fetch(config, batch) })
            }
            futures.forEach { future ->
                val phases = runCatching { future.get() }.getOrNull() ?: return@forEach
                phases.forEach { (phoneKey, values) ->
                    phaseCache[cacheKey(scope, phoneKey)] = values
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun accountScope(config: AppConfig): String =
        "${config.baseUrl.trim().trimEnd('/')}|${config.accessToken.hashCode()}"

    private fun cacheKey(scope: String, phoneKey: String): String = "$scope|$phoneKey"
}

/** Reads phase values for up to 50 phones from the existing Call Report server endpoint. */
internal object CompanyNegotiationPhaseBatchRemoteClient {
    private const val PATH = "/broker/callreport/company_phase_batch.php"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_PHONES = 50

    fun fetch(config: AppConfig, phones: List<String>): Map<String, Set<Int>> {
        val phoneByKey = linkedMapOf<String, String>()
        phones.forEach { phone ->
            val key = HomeCallPageLoader.noteKey(phone)
            if (key.isNotBlank() && phoneByKey.size < MAX_PHONES) phoneByKey.putIfAbsent(key, phone)
        }
        if (phoneByKey.isEmpty()) return emptyMap()

        val endpoint = config.baseUrl.trim().trimEnd('/') + PATH
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Keep both current and transition headers for existing server installs.
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            val payload = JSONObject().apply {
                put("phones", JSONArray().apply { phoneByKey.values.forEach(::put) })
            }.toString()
            connection.outputStream.use { output -> output.write(payload.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input -> BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status !in 200..299 || json?.optBoolean("ok", false) != true) {
                val error = json?.optString("error").orEmpty().trim()
                throw IOException(if (error.isNotBlank()) "$error (HTTP $status)" else "Batch phase request failed ($status).")
            }

            val phasesByPhone = phoneByKey.keys.associateWith { linkedSetOf<Int>() }.toMutableMap()
            val items = json.optJSONArray("items")
            for (index in 0 until (items?.length() ?: 0)) {
                val item = items?.optJSONObject(index) ?: continue
                val phoneKey = HomeCallPageLoader.noteKey(item.optString("phone"))
                val phase = item.optInt("phase", ContactNegotiationPhaseStore.NONE)
                if (phoneKey in phasesByPhone && phase in ContactNegotiationPhaseStore.PHASE_1..ContactNegotiationPhaseStore.PHASE_4) {
                    phasesByPhone.getValue(phoneKey).add(phase)
                }
            }
            return phasesByPhone.mapValues { (_, phases) -> phases.toSet() }
        } finally {
            connection.disconnect()
        }
    }
}

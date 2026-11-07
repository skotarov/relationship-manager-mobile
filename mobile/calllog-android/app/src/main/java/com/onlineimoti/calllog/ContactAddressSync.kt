package com.onlineimoti.calllog

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uploads only addresses entered for numbers without a real Android contact. */
internal object ContactAddressSyncScheduler {
    private const val WORK_NAME = "callreport_contact_address_sync"

    fun enqueue(context: Context) {
        if (ContactAddressReader.pending(context).isEmpty()) return
        val request = OneTimeWorkRequestBuilder<ContactAddressSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

internal class ContactAddressSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val pending = ContactAddressReader.pending(applicationContext)
        if (pending.isEmpty()) return Result.success()
        val config = ConfigStore.load(applicationContext)
        if (!CallReportRemoteAccess.isReady(config)) return Result.retry()
        return runCatching {
            ContactAddressSyncClient.sync(config, pending).forEach { receipt ->
                ContactAddressReader.markSynced(applicationContext, receipt.phoneKey, receipt.updatedAtMs)
            }
            if (ContactAddressReader.pending(applicationContext).isEmpty()) Result.success() else Result.retry()
        }.getOrElse { Result.retry() }
    }
}

internal object ContactAddressSyncClient {
    private const val PATH = "/relationship-manager/api/contact_addresses.php"

    fun sync(config: AppConfig, values: List<ContactAddressReader.StoredAddress>): List<Receipt> {
        val payload = JSONObject().apply {
            put("items", JSONArray().apply {
                values.take(50).forEach { value ->
                    put(JSONObject().apply {
                        put("phone", value.phoneKey)
                        put("addresses", JSONArray(value.addresses))
                        put("updated_at_ms", value.updatedAtMs)
                    })
                }
            })
        }
        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val json = JSONObject(response)
            if (connection.responseCode !in 200..299 || !json.optBoolean("ok", false)) throw IOException("Address sync rejected")
            return buildList {
                val items = json.optJSONArray("items") ?: JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val key = PhoneNormalizer.key(item.optString("normalized_phone"))
                    val updated = item.optLong("updated_at_ms", 0L)
                    if (key.isNotBlank() && updated > 0L) add(Receipt(key, updated))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun fetch(config: AppConfig, phones: Collection<String>): List<RemoteAddress> {
        if (phones.isEmpty()) return emptyList()
        val query = URLEncoder.encode(phones.take(100).joinToString(","), Charsets.UTF_8.name())
        val connection = URL(config.baseUrl.trim().trimEnd('/') + PATH + "?phones=$query").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Relationship-Manager-Token", config.accessToken)
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val json = JSONObject(response)
            if (connection.responseCode !in 200..299 || !json.optBoolean("ok", false)) return emptyList()
            return buildList {
                val items = json.optJSONArray("items") ?: JSONArray()
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val phone = item.optString("phone").trim()
                    val updatedAtMs = item.optLong("updated_at_ms", 0L)
                    val addresses = buildList {
                        val values = item.optJSONArray("addresses") ?: JSONArray()
                        for (addressIndex in 0 until values.length()) add(values.optString(addressIndex))
                    }
                    if (phone.isNotBlank() && updatedAtMs > 0L) add(RemoteAddress(phone, addresses, updatedAtMs))
                }
            }
        } catch (_: IOException) {
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    data class Receipt(val phoneKey: String, val updatedAtMs: Long)
    data class RemoteAddress(val phone: String, val addresses: List<String>, val updatedAtMs: Long)
}

internal object ContactAddressRepository {
    fun forCalls(context: Context, calls: List<PhoneCallRecord>): Map<String, List<String>> {
        val phones = calls.map { it.number }.distinctBy(HomeCallPageLoader::noteKey)
        val unknownPhones = phones.filterNot { ContactAddressReader.isAndroidContact(context, it) }
        val config = ConfigStore.load(context.applicationContext)
        if (unknownPhones.isNotEmpty() && CallReportRemoteAccess.isReady(config)) {
            ContactAddressSyncClient.fetch(config, unknownPhones).forEach { remote ->
                ContactAddressReader.mergeServerAddresses(context, remote.phone, remote.addresses, remote.updatedAtMs)
            }
        }
        return phones.mapNotNull { phone ->
            ContactAddressReader.addressesForPhone(context, phone)
                .takeIf { it.isNotEmpty() }
                ?.let { HomeCallPageLoader.noteKey(phone) to it }
        }.toMap(linkedMapOf())
    }
}

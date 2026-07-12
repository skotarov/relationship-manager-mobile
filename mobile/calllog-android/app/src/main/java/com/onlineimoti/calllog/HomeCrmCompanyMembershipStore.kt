package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A compact per-account cache of the firms already seen for CRM phone numbers.
 * It makes the firm filter usable immediately after reopening Home and prevents
 * repeated history lookups while paging through the same lead list.
 */
internal data class HomeCrmCompanyMembershipResult(
    val companyIdsByPhoneKey: Map<String, Set<String>>,
    val complete: Boolean,
)

internal object HomeCrmCompanyMembershipStore {
    private const val PREFS = "home_crm_company_memberships"
    private const val KEY_SCOPE = "account_scope"
    private const val KEY_ENTRIES = "entries_v1"
    private const val MAX_ENTRIES = 1_500
    private val lock = Any()

    /**
     * Resolves the company membership of every requested phone. Network requests are
     * intentionally made only when a company criterion is active; normal CRM Home
     * remains a fast local Call Log view.
     */
    fun resolve(
        context: Context,
        config: AppConfig,
        phones: List<String>,
    ): HomeCrmCompanyMembershipResult {
        val appContext = context.applicationContext
        val requested = phones
            .associateBy { HomeCallPageLoader.noteKey(it) }
            .filterKeys { it.isNotBlank() }
        if (requested.isEmpty()) return HomeCrmCompanyMembershipResult(emptyMap(), complete = true)

        val scope = scopeFor(config) ?: return HomeCrmCompanyMembershipResult(emptyMap(), complete = false)
        synchronized(lock) {
            var entries = readLocked(appContext, scope)
            val missing = requested.keys.filterNot { it in entries }
            if (missing.isNotEmpty() && CallReportRemoteAccess.isReady(config)) {
                val updates = linkedMapOf<String, MembershipEntry>()
                requested
                    .filterKeys { it in missing }
                    .values
                    .chunked(MAX_PHONES_PER_REQUEST)
                    .forEach { batch ->
                        val result = runCatching {
                            CallReportHistoryLookupClient.lookupMany(config, batch, appContext)
                        }.onFailure { error ->
                            ServerConnectionNotifier.notifyFailure(appContext, config, error)
                        }.getOrNull() ?: return@forEach
                        // lookupMany returns an empty result both for "nothing found" and for
                        // a swallowed network/token failure. Do not cache a negative answer in
                        // that case; otherwise a bad token can make real server clients vanish
                        // until the app cache is cleared.
                        if (result.events.isEmpty()) return@forEach
                        val companiesByPhone = batch.associateBy(
                            keySelector = HomeCallPageLoader::noteKey,
                            valueTransform = { linkedSetOf<String>() },
                        ).toMutableMap()
                        result.events.forEach { event ->
                            val phoneKey = HomeCallPageLoader.noteKey(event.phone)
                            val companyId = event.companyId.trim()
                            if (phoneKey in companiesByPhone && companyId.isNotBlank()) {
                                companiesByPhone.getValue(phoneKey).add(companyId)
                            }
                        }
                        val now = System.currentTimeMillis()
                        companiesByPhone.forEach { (phoneKey, companyIds) ->
                            updates[phoneKey] = MembershipEntry(companyIds.toSet(), now)
                        }
                    }
                if (updates.isNotEmpty()) {
                    entries = entries.toMutableMap().apply { putAll(updates) }
                    writeLocked(appContext, scope, trim(entries))
                }
            }
            val result = requested.keys.mapNotNull { phoneKey ->
                entries[phoneKey]?.let { phoneKey to it.companyIds }
            }.toMap()
            return HomeCrmCompanyMembershipResult(
                companyIdsByPhoneKey = result,
                complete = requested.keys.all { it in entries },
            )
        }
    }

    /**
     * Company membership can change from note/SMS reassignment or CRM switch edits.
     * Drop the stale phone entry so the next company-filter pass re-reads server history.
     */
    fun invalidate(context: Context, phone: String) {
        val phoneKey = HomeCallPageLoader.noteKey(phone)
        if (phoneKey.isBlank()) return
        val appContext = context.applicationContext
        val scope = scopeFor(ConfigStore.load(appContext)) ?: return
        synchronized(lock) {
            val entries = readLocked(appContext, scope)
            if (phoneKey !in entries) return
            writeLocked(appContext, scope, entries - phoneKey)
        }
    }

    fun invalidate(context: Context, phones: Collection<String>) {
        val phoneKeys = phones.mapTo(linkedSetOf()) { HomeCallPageLoader.noteKey(it) }.filterTo(linkedSetOf()) { it.isNotBlank() }
        if (phoneKeys.isEmpty()) return
        val appContext = context.applicationContext
        val scope = scopeFor(ConfigStore.load(appContext)) ?: return
        synchronized(lock) {
            val entries = readLocked(appContext, scope)
            val next = entries.filterKeys { it !in phoneKeys }
            if (next.size == entries.size) return
            writeLocked(appContext, scope, next)
        }
    }

    private fun readLocked(context: Context, scope: String): Map<String, MembershipEntry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_SCOPE, "").orEmpty() != scope) return emptyMap()
        val array = runCatching { JSONArray(prefs.getString(KEY_ENTRIES, "[]").orEmpty()) }.getOrDefault(JSONArray())
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val phoneKey = item.optString("phone_key").trim()
                if (phoneKey.isBlank()) continue
                val companies = buildSet {
                    val source = item.optJSONArray("company_ids") ?: return@buildSet
                    for (companyIndex in 0 until source.length()) {
                        source.optString(companyIndex).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
                val updatedAt = item.optLong("updated_at_ms", 0L)
                put(phoneKey, MembershipEntry(companies, updatedAt))
            }
        }
    }

    private fun writeLocked(context: Context, scope: String, entries: Map<String, MembershipEntry>) {
        val payload = JSONArray().apply {
            entries.forEach { (phoneKey, entry) ->
                put(JSONObject().apply {
                    put("phone_key", phoneKey)
                    put("company_ids", JSONArray().apply { entry.companyIds.sorted().forEach(::put) })
                    put("updated_at_ms", entry.updatedAtMs)
                })
            }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCOPE, scope)
            .putString(KEY_ENTRIES, payload.toString())
            .commit()
    }

    private fun trim(entries: Map<String, MembershipEntry>): Map<String, MembershipEntry> {
        if (entries.size <= MAX_ENTRIES) return entries
        return entries.entries
            .sortedByDescending { it.value.updatedAtMs }
            .take(MAX_ENTRIES)
            .associate { it.toPair() }
    }

    private fun scopeFor(config: AppConfig): String? {
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val token = config.accessToken.trim()
        if (baseUrl.isBlank() || token.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$baseUrl|$token".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class MembershipEntry(
        val companyIds: Set<String>,
        val updatedAtMs: Long,
    )

    // CallReportHistoryLookupClient.lookupMany intentionally falls back to at most
    // 20 single-phone requests. Keep this batch size aligned so the remaining phones
    // are not cached as empty just because lookupMany ignored them.
    private const val MAX_PHONES_PER_REQUEST = 20
}

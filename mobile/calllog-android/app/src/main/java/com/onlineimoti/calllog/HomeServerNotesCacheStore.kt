package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent installation-wide cache for server history used by Home and Clients. */
internal object HomeServerNotesCacheStore {
    private const val PREFS = "home_server_notes_cache"
    private const val KEY_ENTRIES = "entries_v1"
    private const val KEY_COMPANIES = "accessible_companies_v1"
    private val lock = Any()

    fun snapshot(
        context: Context,
        phones: Collection<String>,
    ): CallReportHistoryLookupResult = synchronized(lock) {
        HomeServerNotesCacheMerger.visibleResult(readLocked(context.applicationContext), phones)
    }

    /** Returns true only when the durable cache state really changed. */
    fun update(
        context: Context,
        result: CallReportHistoryLookupResult,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!result.requestSuccessful) return false
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = readLocked(appContext)
            val next = HomeServerNotesCacheMerger.apply(current, result, nowMs)
            if (next == current) return false
            writeLocked(appContext, next)
            return true
        }
    }

    private fun readLocked(context: Context): HomeServerNotesCacheState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val eventsByPhone = linkedMapOf<String, List<CallReportHistoryEvent>>()
        val updatedByPhone = linkedMapOf<String, Long>()
        val groups = runCatching {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]").orEmpty())
        }.getOrDefault(JSONArray())
        for (index in 0 until groups.length()) {
            val group = groups.optJSONObject(index) ?: continue
            val phoneKey = group.optString("phone_key").trim()
            if (phoneKey.isBlank()) continue
            val eventsJson = group.optJSONArray("events") ?: JSONArray()
            val events = buildList {
                for (eventIndex in 0 until eventsJson.length()) {
                    runCatching { parseEvent(eventsJson.optJSONObject(eventIndex) ?: return@runCatching null) }
                        .getOrNull()
                        ?.takeIf { it.phone.isNotBlank() }
                        ?.let(::add)
                }
            }
                .distinctBy(HomeServerNotesCacheMerger::stableEventKey)
                .sortedByDescending(HomeServerNotesCacheMerger::eventVersionMs)
            if (events.isNotEmpty()) eventsByPhone[phoneKey] = events
            updatedByPhone[phoneKey] = group.optLong("updated_at_ms", 0L)
        }

        val principalJson = runCatching {
            JSONObject(prefs.getString(KEY_COMPANIES, "{}").orEmpty())
        }.getOrDefault(JSONObject())
        val companies = buildList {
            val source = principalJson.optJSONArray("companies") ?: JSONArray()
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                add(CallReportHistoryCompany(id, item.optString("name").trim().ifBlank { id }))
            }
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        val principal = CallReportHistoryPrincipal(
            brokerId = principalJson.optString("broker_id").trim(),
            brokerName = principalJson.optString("broker_name").trim(),
            companies = companies,
        )
        return HomeServerNotesCacheState(
            eventsByPhoneKey = eventsByPhone,
            phoneUpdatedAtMs = updatedByPhone,
            principal = principal,
            accessibleCompaniesAuthoritative = principalJson.optBoolean("authoritative", false),
        )
    }

    private fun writeLocked(context: Context, state: HomeServerNotesCacheState) {
        val groups = JSONArray().apply {
            state.eventsByPhoneKey
                .toSortedMap()
                .forEach { (phoneKey, events) ->
                    put(JSONObject().apply {
                        put("phone_key", phoneKey)
                        put("updated_at_ms", state.phoneUpdatedAtMs[phoneKey] ?: 0L)
                        put("events", JSONArray().apply {
                            events
                                .sortedByDescending(HomeServerNotesCacheMerger::eventVersionMs)
                                .forEach { put(eventJson(it)) }
                        })
                    })
                }
        }
        val principal = JSONObject().apply {
            put("authoritative", state.accessibleCompaniesAuthoritative)
            put("broker_id", state.principal.brokerId)
            put("broker_name", state.principal.brokerName)
            put("companies", JSONArray().apply {
                state.principal.companies
                    .distinctBy { it.id }
                    .sortedBy { it.name.lowercase() }
                    .forEach { company ->
                        put(JSONObject().apply {
                            put("id", company.id)
                            put("name", company.name)
                        })
                    }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, groups.toString())
            .putString(KEY_COMPANIES, principal.toString())
            .commit()
    }

    private fun eventJson(event: CallReportHistoryEvent): JSONObject = JSONObject().apply {
        put("server_id", event.serverId)
        put("client_event_id", event.clientEventId)
        put("communication_type", event.communicationType)
        put("phone", event.phone)
        put("direction", event.direction)
        put("status", event.status)
        put("occurred_at_ms", event.occurredAtMs)
        put("duration_seconds", event.durationSeconds)
        put("note", event.note)
        put("contact_name", event.contactName)
        put("created_at_ms", event.createdAtMs)
        put("updated_at_ms", event.updatedAtMs)
        put("author_broker_id", event.authorBrokerId)
        put("author_broker_name", event.authorBrokerName)
        put("company_id", event.companyId)
    }

    private fun parseEvent(json: JSONObject): CallReportHistoryEvent = CallReportHistoryEvent(
        serverId = json.optString("server_id").trim(),
        clientEventId = json.optString("client_event_id").trim(),
        communicationType = json.optString("communication_type", "phone").trim().ifBlank { "phone" },
        phone = json.optString("phone").trim(),
        direction = json.optString("direction").trim(),
        status = json.optString("status").trim(),
        occurredAtMs = json.optLong("occurred_at_ms", 0L),
        durationSeconds = json.optLong("duration_seconds", 0L),
        note = json.optString("note").trim(),
        contactName = json.optString("contact_name").trim(),
        createdAtMs = json.optLong("created_at_ms", 0L),
        updatedAtMs = json.optLong("updated_at_ms", 0L),
        authorBrokerId = json.optString("author_broker_id").trim(),
        authorBrokerName = json.optString("author_broker_name").trim(),
        companyId = json.optString("company_id").trim(),
    )
}

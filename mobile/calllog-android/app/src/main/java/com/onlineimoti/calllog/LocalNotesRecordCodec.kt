package com.onlineimoti.calllog

import org.json.JSONObject
import java.security.MessageDigest

internal object LocalNotesRecordCodec {
    fun clientNoteIdForCall(phoneNumber: String, callAt: Long, direction: String): String {
        if (callAt <= 0L) return ""
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank()) return ""
        val canonical = "$phoneKey|$callAt|${direction.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
    }

    fun callNotes(lines: List<String>, phoneNumber: String): List<ContactCallNote> {
        return lines.mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val type = json.optString("type")
                val callAt = json.optLong("call_at", 0L)
                if (type.isNotBlank() && type != "call_note") return@runCatching null
                if (type.isBlank() && callAt <= 0L) return@runCatching null
                val note = json.optString("note").trim()
                val direction = json.optString("direction")
                ContactCallNote(
                    note = note,
                    callAt = callAt,
                    savedAt = json.optLong("at", 0L),
                    direction = direction,
                    durationSeconds = json.optLong("duration", 0L),
                    clientNoteId = json.optString("client_note_id").trim()
                        .ifBlank { clientNoteIdForCall(phoneNumber, callAt, direction) },
                    companyId = json.optString("company_id").trim(),
                    serverClientEventId = json.optString("server_client_event_id").trim(),
                )
            }.getOrNull()
        }
    }

    fun sameCall(json: JSONObject, callAt: Long, direction: String): Boolean {
        if (json.optString("type") != "call_note" && json.optLong("call_at", 0L) <= 0L) return false
        if (json.optLong("call_at", 0L) != callAt) return false
        val storedDirection = json.optString("direction")
        return direction.isBlank() || storedDirection.isBlank() || storedDirection == direction
    }

    fun storedGeneralNote(text: String): LocalStoredGeneralNote? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
        val phoneKey = PhoneNormalizer.key(json.optString("normalized_phone").ifBlank { phone })
        if (phoneKey.isBlank()) return null
        val note = json.optString("general_note").trim()
            .ifBlank { json.optString("note").trim() }
        if (note.isBlank()) return null
        return LocalStoredGeneralNote(
            phone = phone.ifBlank { phoneKey },
            phoneKey = phoneKey,
            note = note,
            noteAt = maxOf(json.optLong("general_note_at", 0L), json.optLong("updated_at", 0L)),
        )
    }

    fun storedCallNotes(lines: List<String>): List<LocalStoredCallNote> = runCatching {
        lines.mapNotNull { line ->
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val type = json.optString("type")
            val callAt = json.optLong("call_at", 0L)
            if (type.isNotBlank() && type != "call_note") return@mapNotNull null
            if (type.isBlank() && callAt <= 0L) return@mapNotNull null
            val note = json.optString("note").trim()
            if (note.isBlank()) return@mapNotNull null
            val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
            val phoneKey = PhoneNormalizer.key(json.optString("normalized_phone").ifBlank { phone })
            if (phoneKey.isBlank()) return@mapNotNull null
            LocalStoredCallNote(
                phone = phone.ifBlank { phoneKey },
                phoneKey = phoneKey,
                note = note,
                noteAt = json.optLong("at", 0L),
                callAt = callAt,
                direction = json.optString("direction"),
                durationSeconds = json.optLong("duration", 0L),
            )
        }
    }.getOrDefault(emptyList())
}

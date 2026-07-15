package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject

internal object LocalGeneralNotesStore {
    fun profileGeneralNote(context: Context, phoneNumber: String): String {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || !LocalNotesWorkspace.canUseConfiguredFolder(context)) return ""
        val ref = LocalNotesIo.profileRef(context, phoneKey, createDirs = false) ?: return ""
        if (!LocalNotesIo.exists(ref)) return ""
        return runCatching {
            val json = JSONObject(LocalNotesIo.readText(context, ref))
            // latest_note tracks the most recent blue/call note and must never be
            // shown as the yellow/general note after the general note is deleted.
            json.optString("general_note").trim()
                .ifBlank { json.optString("note").trim() }
        }.getOrDefault("")
    }

    fun saveUnknownGeneralNote(context: Context, phoneNumber: String, note: String): Boolean {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || !LocalNotesWorkspace.canUseConfiguredFolder(context)) return false
        return runCatching {
            val now = System.currentTimeMillis()
            val ref = LocalNotesIo.profileRef(context, phoneKey, createDirs = true) ?: return false
            val profile = if (LocalNotesIo.exists(ref)) {
                runCatching { JSONObject(LocalNotesIo.readText(context, ref)) }
                    .getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            profile.put("v", 1)
            profile.put("phone", phoneNumber)
            profile.put("normalized_phone", phoneKey)
            profile.put("has_android_contact", false)
            profile.put("general_note", note.trim())
            profile.put("general_note_at", now)
            profile.put("updated_at", now)
            LocalNotesIo.writeText(context, ref, profile.toString(2))
            true
        }.getOrDefault(false)
    }

    fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean {
        val phoneKey = PhoneNormalizer.key(phoneNumber)
        if (phoneKey.isBlank() || !LocalNotesWorkspace.canUseConfiguredFolder(context)) return true
        return runCatching {
            val ref = LocalNotesIo.profileRef(context, phoneKey, createDirs = false)
                ?: return@runCatching true
            if (!LocalNotesIo.exists(ref)) return@runCatching true
            val profile = runCatching { JSONObject(LocalNotesIo.readText(context, ref)) }
                .getOrDefault(JSONObject())
            profile.remove("general_note")
            profile.remove("general_note_at")
            profile.remove("note")
            cleanupOrWriteProfile(context, ref, phoneNumber, phoneKey, profile)
            true
        }.getOrDefault(false)
    }

    fun storedGeneralNotes(context: Context): List<LocalStoredGeneralNote> {
        if (!LocalNotesWorkspace.canUseConfiguredFolder(context)) return emptyList()
        return LocalNotesIo.profileRefs(context)
            .mapNotNull { ref -> parseStoredGeneralNote(context, ref) }
            .filter { it.note.isNotBlank() && it.phoneKey.isNotBlank() }
    }

    fun writeUnknownLatestProfile(
        context: Context,
        phoneNumber: String,
        phoneKey: String,
        latestNote: String,
        updatedAt: Long,
    ) {
        val ref = LocalNotesIo.profileRef(context, phoneKey, createDirs = true) ?: return
        val profile = if (LocalNotesIo.exists(ref)) {
            runCatching { JSONObject(LocalNotesIo.readText(context, ref)) }
                .getOrDefault(JSONObject())
        } else {
            JSONObject()
        }
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put("latest_note", latestNote)
        profile.put("latest_note_at", updatedAt)
        profile.put("updated_at", updatedAt)
        LocalNotesIo.writeText(context, ref, profile.toString(2))
    }

    fun refreshLatestProfileNote(context: Context, phoneNumber: String, phoneKey: String) {
        if (!LocalNotesWorkspace.canUseConfiguredFolder(context)) return
        runCatching {
            val profileRef = LocalNotesIo.profileRef(context, phoneKey, createDirs = false)
                ?: return@runCatching
            if (!LocalNotesIo.exists(profileRef)) return@runCatching
            val profile = runCatching { JSONObject(LocalNotesIo.readText(context, profileRef)) }
                .getOrDefault(JSONObject())
            val latestLine = LocalNotesIo.readLastNonBlankLine(
                context,
                LocalNotesIo.callLogRef(context, phoneKey, createDirs = false),
            )
            val latestNote = if (latestLine.isBlank()) {
                ""
            } else {
                runCatching { JSONObject(latestLine).optString("note") }
                    .getOrDefault("")
                    .trim()
            }
            if (latestNote.isBlank()) {
                profile.remove("latest_note")
                profile.remove("latest_note_at")
            } else {
                profile.put("latest_note", latestNote)
                profile.put("latest_note_at", System.currentTimeMillis())
            }
            cleanupOrWriteProfile(context, profileRef, phoneNumber, phoneKey, profile)
        }
    }

    private fun parseStoredGeneralNote(
        context: Context,
        ref: LocalNoteFileRef,
    ): LocalStoredGeneralNote? {
        val json = runCatching { JSONObject(LocalNotesIo.readText(context, ref)) }
            .getOrNull() ?: return null
        val phone = json.optString("phone").ifBlank { json.optString("normalized_phone") }
        val phoneKey = PhoneNormalizer.key(
            json.optString("normalized_phone").ifBlank { phone },
        )
        if (phoneKey.isBlank()) return null
        val note = json.optString("general_note").trim()
            .ifBlank { json.optString("note").trim() }
        if (note.isBlank()) return null
        return LocalStoredGeneralNote(
            phone = phone.ifBlank { phoneKey },
            phoneKey = phoneKey,
            note = note,
            noteAt = maxOf(
                json.optLong("general_note_at", 0L),
                json.optLong("updated_at", 0L),
            ),
        )
    }

    private fun cleanupOrWriteProfile(
        context: Context,
        ref: LocalNoteFileRef,
        phoneNumber: String,
        phoneKey: String,
        profile: JSONObject,
    ) {
        val hasGeneral = profile.optString("general_note").trim().isNotBlank() ||
            profile.optString("note").trim().isNotBlank()
        val hasLatest = profile.optString("latest_note").trim().isNotBlank()
        if (!hasGeneral && !hasLatest) {
            LocalNotesIo.delete(ref)
            return
        }
        profile.put("v", 1)
        profile.put("phone", phoneNumber)
        profile.put("normalized_phone", phoneKey)
        profile.put("has_android_contact", false)
        profile.put(
            "storage",
            when {
                LocalNotesWorkspace.usesSelectedFolder(context) -> "selected"
                LocalNotesWorkspace.usesPublicFolder(context) -> "public"
                else -> "private"
            },
        )
        profile.put("updated_at", System.currentTimeMillis())
        LocalNotesIo.writeText(context, ref, profile.toString(2))
    }
}

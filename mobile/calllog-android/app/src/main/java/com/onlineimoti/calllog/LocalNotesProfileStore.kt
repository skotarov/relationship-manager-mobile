package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONObject

internal object LocalNotesProfileStore {
    fun generalNote(context: Context, phoneNumber: String): LocalGeneralNote {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank()) return LocalGeneralNote("", 0L)
        val ref = LocalNotesFileAccess.profileRef(context, key, createDirs = false)
            ?: return LocalGeneralNote("", 0L)
        if (!LocalNotesFileAccess.exists(ref)) return LocalGeneralNote("", 0L)
        return runCatching {
            val json = JSONObject(LocalNotesFileAccess.readText(context, ref))
            LocalGeneralNote(
                note = json.optString("general_note").trim(),
                updatedAt = maxOf(
                    json.optLong("general_note_at", 0L),
                    json.optLong("updated_at", 0L),
                ),
            )
        }.getOrDefault(LocalGeneralNote("", 0L))
    }

    fun saveGeneralNote(context: Context, phoneNumber: String, note: String): Boolean {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank() || !ensureStorage(context)) return false
        val ref = LocalNotesFileAccess.profileRef(context, key, createDirs = true) ?: return false
        val current = if (LocalNotesFileAccess.exists(ref)) {
            runCatching { JSONObject(LocalNotesFileAccess.readText(context, ref)) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val now = System.currentTimeMillis()
        current.put("phone", PhoneNormalizer.display(phoneNumber))
        current.put("normalized_phone", PhoneNormalizer.normalize(phoneNumber))
        current.put("general_note", note.trim())
        current.put("general_note_at", now)
        current.put("updated_at", now)
        LocalNotesFileAccess.writeText(context, ref, current.toString(2))
        return true
    }

    fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank()) return false
        val ref = LocalNotesFileAccess.profileRef(context, key, createDirs = false) ?: return true
        if (!LocalNotesFileAccess.exists(ref)) return true
        return runCatching {
            val current = JSONObject(LocalNotesFileAccess.readText(context, ref))
            current.remove("general_note")
            current.remove("general_note_at")
            current.put("updated_at", System.currentTimeMillis())
            LocalNotesFileAccess.writeText(context, ref, current.toString(2))
            true
        }.getOrDefault(false)
    }

    fun refreshProfileFromPhonebook(
        context: Context,
        phoneNumber: String,
        contactName: String,
        groups: Set<String>,
        website: String = "",
        note: String = "",
    ): Boolean {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank() || !ensureStorage(context)) return false
        val ref = LocalNotesFileAccess.profileRef(context, key, createDirs = true) ?: return false
        return runCatching {
            val current = if (LocalNotesFileAccess.exists(ref)) {
                runCatching { JSONObject(LocalNotesFileAccess.readText(context, ref)) }
                    .getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            current.put("phone", PhoneNormalizer.display(phoneNumber))
            current.put("normalized_phone", PhoneNormalizer.normalize(phoneNumber))
            current.put("name", contactName.trim())
            current.put("groups", org.json.JSONArray(groups.sorted()))
            current.put("website", website.trim())
            current.put("contact_note", note.trim())
            current.put("source", "phonebook")
            current.put("updated_at", System.currentTimeMillis())
            LocalNotesFileAccess.writeText(context, ref, current.toString(2))
            true
        }.getOrDefault(false)
    }

    fun writeUnknownProfile(context: Context, phoneNumber: String): Boolean {
        val key = PhoneNormalizer.key(phoneNumber)
        if (key.isBlank() || !ensureStorage(context)) return false
        val ref = LocalNotesFileAccess.profileRef(context, key, createDirs = true) ?: return false
        return runCatching {
            val current = if (LocalNotesFileAccess.exists(ref)) {
                runCatching { JSONObject(LocalNotesFileAccess.readText(context, ref)) }
                    .getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            current.put("phone", PhoneNormalizer.display(phoneNumber))
            current.put("normalized_phone", PhoneNormalizer.normalize(phoneNumber))
            current.put("name", "")
            current.put("groups", org.json.JSONArray())
            current.put("website", "")
            current.put("contact_note", "")
            current.put("source", "unknown")
            current.put("updated_at", System.currentTimeMillis())
            LocalNotesFileAccess.writeText(context, ref, current.toString(2))
            true
        }.getOrDefault(false)
    }

    fun cleanupMissingPhonebookProfiles(context: Context, activePhoneKeys: Set<String>): Int {
        var removed = 0
        LocalNotesFileAccess.profileRefs(context).forEach { ref ->
            val json = runCatching { JSONObject(LocalNotesFileAccess.readText(context, ref)) }
                .getOrNull() ?: return@forEach
            val source = json.optString("source")
            val key = PhoneNormalizer.key(json.optString("normalized_phone"))
            if (source == "phonebook" && key.isNotBlank() && key !in activePhoneKeys) {
                LocalNotesFileAccess.delete(ref)
                removed++
            }
        }
        return removed
    }

    private fun ensureStorage(context: Context): Boolean {
        return if (LocalNotesStorageLocation.usesSelectedFolder(context)) {
            LocalNotesStorageLocation.hasSelectedFolderAccess(context) &&
                LocalNotesStorageLocation.ensureSelectedStorage(context)
        } else {
            (!LocalNotesStorageLocation.usesPublicFolder(context) ||
                LocalNotesStorageLocation.canUsePublicFolder(context)) &&
                LocalNotesStorageLocation.ensurePlainStorage(context)
        }
    }
}

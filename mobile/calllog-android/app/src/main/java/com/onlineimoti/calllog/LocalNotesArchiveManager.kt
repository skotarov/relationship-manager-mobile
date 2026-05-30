package com.onlineimoti.calllog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object LocalNotesArchiveManager {
    private const val LOCAL_NOTE_PREFS = "callreport_local_contact_notes"
    private const val NOTES_DIR = "notes"
    private const val CALL_LOG_FILE = "calllog.notes"
    private const val PROFILE_FILE = "profile.json"

    enum class RestoreMode { ClearAndRestore, Merge }

    data class RestoreSummary(
        val generalNotes: Int,
        val files: Int,
        val mode: RestoreMode,
    )

    fun createArchiveJson(context: Context): String {
        val root = activeNotesRoot(context)
        val generalNotes = JSONObject()
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            val note = value as? String ?: return@forEach
            if (key.isNotBlank() && note.isNotBlank()) generalNotes.put(key, note)
        }

        val files = JSONArray()
        val notesDir = File(root, NOTES_DIR)
        if (notesDir.exists()) {
            notesDir.walkTopDown().filter { it.isFile }.forEach { file ->
                files.put(
                    JSONObject()
                        .put("path", file.relativeTo(root).invariantSeparatorsPath)
                        .put("content", file.readText())
                )
            }
        }

        return JSONObject()
            .put("v", 1)
            .put("app", "Call Log")
            .put("created_at", System.currentTimeMillis())
            .put("storage_root", LocalNotesFileStore.activeRootPath(context))
            .put("general_notes", generalNotes)
            .put("files", files)
            .toString(2)
    }

    fun restoreArchiveJson(context: Context, archiveText: String, mode: RestoreMode): RestoreSummary {
        val archive = JSONObject(archiveText)
        val root = activeNotesRoot(context)
        if (mode == RestoreMode.ClearAndRestore) clearLocalNotesData(context, root)
        val generalCount = restoreGeneralNotes(context, archive.optJSONObject("general_notes") ?: JSONObject())
        val fileCount = restoreFiles(root, archive.optJSONArray("files") ?: JSONArray(), mode)
        return RestoreSummary(generalNotes = generalCount, files = fileCount, mode = mode)
    }

    private fun activeNotesRoot(context: Context): File {
        if (!LocalNotesFileStore.canUseConfiguredFolder(context)) {
            error("Избрана е обща файлова система, но Android разрешението липсва.")
        }
        return File(LocalNotesFileStore.activeRootPath(context)).apply { mkdirs() }
    }

    private fun clearLocalNotesData(context: Context, root: File) {
        context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        File(root, NOTES_DIR).deleteRecursively()
    }

    private fun restoreGeneralNotes(context: Context, generalNotes: JSONObject): Int {
        var count = 0
        val editor = context.getSharedPreferences(LOCAL_NOTE_PREFS, Context.MODE_PRIVATE).edit()
        generalNotes.keys().forEach { key ->
            val note = generalNotes.optString(key).trim()
            if (key.isNotBlank() && note.isNotBlank()) {
                editor.putString(key, note)
                count += 1
            }
        }
        editor.apply()
        return count
    }

    private fun restoreFiles(root: File, files: JSONArray, mode: RestoreMode): Int {
        var count = 0
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            val path = item.optString("path").trim()
            val content = item.optString("content")
            if (!isSafeRelativePath(path)) continue
            val target = File(root, path)
            target.parentFile?.mkdirs()
            when {
                mode == RestoreMode.ClearAndRestore || !target.exists() -> target.writeText(content)
                target.name == CALL_LOG_FILE -> mergeCallLogFile(target, content)
                target.name == PROFILE_FILE -> mergeProfileFile(target, content)
            }
            count += 1
        }
        return count
    }

    private fun mergeCallLogFile(target: File, archiveContent: String) {
        val existing = if (target.exists()) target.readLines().filter { it.isNotBlank() } else emptyList()
        val seen = existing.mapTo(linkedSetOf()) { lineKey(it) }
        val merged = existing.toMutableList()
        archiveContent.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            if (seen.add(lineKey(line))) merged.add(line)
        }
        target.writeText(merged.joinToString("\n") + if (merged.isNotEmpty()) "\n" else "")
    }

    private fun mergeProfileFile(target: File, archiveContent: String) {
        val current = if (target.exists()) runCatching { JSONObject(target.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        val incoming = runCatching { JSONObject(archiveContent) }.getOrDefault(JSONObject())
        incoming.keys().forEach { key -> current.put(key, incoming.opt(key)) }
        target.writeText(current.toString(2))
    }

    private fun lineKey(line: String): String {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return line
        return json.optString("id").takeIf { it.isNotBlank() }
            ?: listOf(json.optString("normalized_phone"), json.optLong("call_at", 0L).toString(), json.optString("direction"), json.optString("note")).joinToString("|")
    }

    private fun isSafeRelativePath(path: String): Boolean {
        return path.isNotBlank() && !path.startsWith("/") && !path.contains("..") && path.startsWith("$NOTES_DIR/")
    }
}
package com.onlineimoti.calllog

import android.content.Context
import android.net.Uri
import java.io.File

internal data class LocalStoredGeneralNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
)

internal data class LocalStoredCallNote(
    val phone: String,
    val phoneKey: String,
    val note: String,
    val noteAt: Long,
    val callAt: Long,
    val direction: String,
    val durationSeconds: Long,
)

/**
 * Stable public facade for local note storage.
 *
 * Storage selection, SAF traversal, migration, profile JSON and call-note JSON are
 * implemented in focused internal modules. Existing callers keep the same API and
 * the same file names, directory structure and JSON keys.
 */
object LocalNotesFileStore {
    fun canUsePublicFolder(): Boolean = LocalNotesWorkspace.canUsePublicFolder()

    fun isEnabled(context: Context): Boolean = LocalNotesWorkspace.isEnabled(context)

    fun selectedFolderUri(context: Context): Uri? =
        LocalNotesWorkspace.selectedFolderUri(context)

    fun hasSelectedFolderAccess(context: Context): Boolean =
        LocalNotesWorkspace.hasSelectedFolderAccess(context)

    fun setSelectedFolder(context: Context, uri: Uri) =
        LocalNotesWorkspace.setSelectedFolder(context, uri)

    fun clearSelectedFolder(context: Context) =
        LocalNotesWorkspace.clearSelectedFolder(context)

    fun shouldUsePublicFolder(context: Context): Boolean =
        LocalNotesWorkspace.shouldUsePublicFolder(context)

    fun usesSelectedFolder(context: Context): Boolean =
        LocalNotesWorkspace.usesSelectedFolder(context)

    fun usesPublicFolder(context: Context): Boolean =
        LocalNotesWorkspace.usesPublicFolder(context)

    fun canUseConfiguredFolder(context: Context): Boolean =
        LocalNotesWorkspace.canUseConfiguredFolder(context)

    fun publicRootPath(): String = LocalNotesWorkspace.publicRootPath()

    fun privateRootPath(context: Context): String =
        LocalNotesWorkspace.privateRootPath(context)

    fun activeRootPath(context: Context): String =
        LocalNotesWorkspace.activeRootPath(context)

    fun migratePrivateToSelected(context: Context): Boolean =
        LocalNotesMigration.migratePrivateToSelected(context)

    fun migratePrivateToPublic(context: Context): Boolean =
        LocalNotesMigration.migratePrivateToPublic(context)

    /** Used only for app-private files that are not local notes. */
    internal fun appPrivateRoot(context: Context): File =
        LocalNotesWorkspace.appPrivateRoot(context)

    fun latestNoteForPhone(context: Context, phoneNumber: String): String =
        LocalCallNotesStore.latestNoteForPhone(context, phoneNumber)

    /**
     * The Home Call Log may show a blue note only when it belongs to this exact
     * call-log row. The latest note for the number belongs to the caller-info
     * popup, not to an unrelated historical row.
     */
    fun noteForCall(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String = LocalCallNotesStore.noteForCall(
        context,
        phoneNumber,
        callAt,
        direction,
    )

    fun companyIdForCall(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String = LocalCallNotesStore.companyIdForCall(
        context,
        phoneNumber,
        callAt,
        direction,
    )

    fun clientNoteIdForCall(
        phoneNumber: String,
        callAt: Long,
        direction: String = "",
    ): String = LocalCallNotesStore.clientNoteIdForCall(
        phoneNumber,
        callAt,
        direction,
    )

    fun allCallNotes(context: Context, phoneNumber: String): List<ContactCallNote> =
        LocalCallNotesStore.allCallNotes(context, phoneNumber)

    fun profileGeneralNote(context: Context, phoneNumber: String): String =
        LocalGeneralNotesStore.profileGeneralNote(context, phoneNumber)

    fun saveUnknownGeneralNote(
        context: Context,
        phoneNumber: String,
        note: String,
    ): Boolean = LocalGeneralNotesStore.saveUnknownGeneralNote(
        context,
        phoneNumber,
        note,
    )

    fun deleteGeneralNote(context: Context, phoneNumber: String): Boolean =
        LocalGeneralNotesStore.deleteGeneralNote(context, phoneNumber)

    fun appendCallNote(
        context: Context,
        phoneNumber: String,
        note: String,
        direction: String = "",
        callAt: Long = 0L,
        durationSeconds: Long = 0L,
        isUnknownContact: Boolean = false,
        companyId: String = "",
    ): Boolean = LocalCallNotesStore.appendCallNote(
        context = context,
        phoneNumber = phoneNumber,
        note = note,
        direction = direction,
        callAt = callAt,
        durationSeconds = durationSeconds,
        isUnknownContact = isUnknownContact,
        companyId = companyId,
    )

    fun deleteCallNote(
        context: Context,
        phoneNumber: String,
        callAt: Long,
        direction: String,
    ): Boolean = LocalCallNotesStore.deleteCallNote(
        context,
        phoneNumber,
        callAt,
        direction,
    )

    internal fun storedGeneralNotes(context: Context): List<LocalStoredGeneralNote> =
        LocalGeneralNotesStore.storedGeneralNotes(context)

    internal fun storedCallNotes(context: Context): List<LocalStoredCallNote> =
        LocalCallNotesStore.storedCallNotes(context)
}

package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout

/** Owns merged History paging and section rendering. */
internal class MergedHistoryPresentation(
    private val activity: Activity,
    private val state: MergedHistoryState,
    private val dp: (Int) -> Int,
    roundedRect: (Int, Int, Int, Int) -> GradientDrawable,
    private val rerender: () -> Unit,
    private val isLoading: () -> Boolean,
) {
    private val rowsUi by lazy { CallReportHistoryRowsUi(activity, dp, roundedRect) }
    private val fullLogUi by lazy { ContactNotesFullLogUi(activity, dp, roundedRect) }

    fun canPreviousNotesPage(): Boolean = rowsUi.canPreviousPage()
    fun canNextNotesPage(): Boolean = rowsUi.canNextPage()
    fun previousNotesPage(): Boolean = rowsUi.previousPage(rerender)
    fun nextNotesPage(): Boolean = rowsUi.nextPage(rerender)
    fun resetNotesPage() = rowsUi.resetPage()

    fun canPreviousFullLogPage(): Boolean = fullLogUi.canPreviousPage()
    fun canNextFullLogPage(): Boolean = fullLogUi.canNextPage()
    fun previousFullLogPage(): Boolean = fullLogUi.previousPage(rerender)
    fun nextFullLogPage(): Boolean = fullLogUi.nextPage(rerender)
    fun resetFullLogPage() = fullLogUi.resetPage()

    fun resetPages() {
        rowsUi.resetPage()
        fullLogUi.resetPage()
    }

    fun addNotesSection(
        root: LinearLayout,
        phone: String,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        MergedHistoryServerFeedback.addBelowContactName(activity, root, remoteEnabled, state.loadError, dp)
        rowsUi.addSection(
            root = root,
            phone = phone,
            remoteEnabled = remoteEnabled,
            principal = state.serverHistory.principal,
            rows = state.prepared.rows,
            latestLocalCall = state.latestLocalCall,
            localNotes = state.localNotes,
            localLoading = state.localLoading || state.prepareLoading,
            serverLoading = state.serverLoading,
            onEditCallNote = onEditCallNote,
            onEditSms = onEditSms,
            onPageChanged = rerender,
        )
    }

    fun addFullLogSection(
        root: LinearLayout,
        phone: String,
        openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    ) {
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        MergedHistoryServerFeedback.addBelowContactName(activity, root, remoteEnabled, state.loadError, dp)
        fullLogUi.addSection(
            root = root,
            phone = phone,
            incomingEntries = state.prepared.fullLogEntries,
            remoteEnabled = remoteEnabled,
            loading = isLoading(),
            errorText = state.loadError,
            openCallNoteEditor = openCallNoteEditor,
        )
    }
}

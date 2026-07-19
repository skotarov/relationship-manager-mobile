package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout

internal class CallReportHistoryNoteRowUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val shared: CallReportHistorySharedUi,
) {
    fun create(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): View {
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val readOnlyNote = row.kind == CallReportHistoryRowKind.NOTE && !row.editable
        val serverConfirmed = shared.isServerConfirmed(phone, row)
        val localNote = row.localNote
        val pendingGenericSync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let { CallReportNoteOutbox.isCallPending(activity, phone, it) } == true
        val pendingNewCompanySync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                CompanyCallNoteOutbox.isCallPending(
                    activity,
                    phone,
                    localNote?.direction ?: row.direction,
                    localNote?.callAt ?: row.timeMs,
                )
        val pendingLegacyCompanySync =
            !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let {
                    CallReportTopicNoteOutbox.isCallPending(activity, phone, it.direction, it.callAt)
                } == true
        val pendingCompanySync = pendingNewCompanySync || pendingLegacyCompanySync
        val pendingCompanyChoice =
            !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE &&
                localNote?.let {
                    CallReportDeferredCompanyAssignmentStore.isCallPending(
                        activity, phone, it.direction, it.callAt,
                    )
                } == true
        val colors = when {
            readOnlyNote -> Triple(
                CallReportHistorySharedUi.FOREIGN_BACKGROUND,
                CallReportHistorySharedUi.FOREIGN_BORDER,
                CallReportHistorySharedUi.FOREIGN_TEXT,
            )
            row.kind == CallReportHistoryRowKind.NOTE -> Triple(
                NoteUiStyle.Call.background,
                NoteUiStyle.Call.border,
                NoteUiStyle.Call.text,
            )
            else -> Triple(Color.WHITE, Color.rgb(226, 232, 240), Color.rgb(30, 41, 59))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.first, dp(12), colors.second, dp(1))
            if (readOnlyNote) {
                val author = row.authorName.ifBlank { "друг потребител" }
                contentDescription = "Неактивна бележка. Записал: $author."
                isClickable = false
                isFocusable = false
                isLongClickable = false
                setOnClickListener(null)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            if (row.kind == CallReportHistoryRowKind.NOTE && row.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val source = row.localNote?.let { existingLocalNote ->
                        val serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                        if (serverClientEventId.isBlank() || existingLocalNote.serverClientEventId == serverClientEventId) {
                            existingLocalNote
                        } else {
                            existingLocalNote.copy(serverClientEventId = serverClientEventId)
                        }
                    } ?: ContactCallNote(
                        note = row.text,
                        callAt = row.timeMs,
                        savedAt = row.serverEvent?.updatedAtMs ?: row.timeMs,
                        direction = row.direction,
                        durationSeconds = row.durationSeconds,
                        clientNoteId = LocalNotesFileStore.clientNoteIdForCall(phone, row.timeMs, row.direction),
                        companyId = row.companyId,
                        serverClientEventId = row.serverEvent?.clientEventId.orEmpty(),
                    )
                    val editableNote = if (remoteEnabled && row.serverNewer) {
                        source.copy(
                            note = row.text,
                            savedAt = maxOf(source.savedAt, row.serverEvent?.updatedAtMs ?: 0L),
                            companyId = row.companyId.ifBlank { source.companyId },
                            serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                                .ifBlank { source.serverClientEventId },
                        )
                    } else {
                        source.copy(
                            companyId = row.companyId.ifBlank { source.companyId },
                            serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                                .ifBlank { source.serverClientEventId },
                        )
                    }
                    onEditCallNote(editableNote)
                }
            }
            addView(shared.metaView(row, muted = readOnlyNote))
            shared.companyLabel(row.companyId, companyNames, muted = readOnlyNote)?.let(::addView)
            if (row.text.isNotBlank()) addView(shared.noteText(row.text, colors.third))
            when {
                pendingCompanyChoice -> addView(shared.pendingCompanyChoiceText())
                pendingCompanySync -> addView(shared.pendingSyncText(
                    if (pendingNewCompanySync) "" else CallReportTopicNoteOutbox.lastFailure(activity),
                ))
                pendingGenericSync && !serverConfirmed -> addView(
                    shared.pendingSyncText(CallReportNoteOutbox.lastFailure(activity)),
                )
            }
            if (readOnlyNote) addView(shared.authorText(row.authorName.ifBlank { "друг потребител" }))
            if (!foreignRecord && remoteEnabled && row.serverNewer) addView(shared.serverNewerText())
        }
    }
}

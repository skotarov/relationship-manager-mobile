package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Renders the Notes and SMS card; loading and remote state stay in its controller. */
internal class CallReportHistoryRowsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean, errorText: String) {
        if (!remoteEnabled || errorText.isBlank()) return
        root.addView(TextView(activity).apply {
            text = errorText
            textSize = 12.5f
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(2), 0, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }, minOf(1, root.childCount))
    }

    fun addSection(
        root: LinearLayout,
        phone: String,
        remoteEnabled: Boolean,
        principal: CallReportHistoryPrincipal,
        serverEvents: List<CallReportHistoryEvent>,
        latestLocalCall: PhoneCallRecord?,
        localSms: List<SmsMessageRecord>,
        localNotes: List<ContactCallNote>,
        localLoading: Boolean,
        serverLoading: Boolean,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        val rows = CallReportHistoryMerge.merge(
            context = activity,
            phone = phone,
            principal = if (remoteEnabled) principal else CallReportHistoryPrincipal(),
            localCalls = emptyList(),
            localSms = localSms,
            localNotes = localNotes,
            serverEvents = if (remoteEnabled) serverEvents else emptyList(),
        )
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
            addView(titleRow(openFilteredLog))
            latestCallWithoutNote(latestLocalCall, localNotes)?.let { call ->
                addView(addLatestCallNoteCard(call) { onEditCallNote(call.toContactCallNote()) })
            }
            rows.forEach { row -> addView(historyRow(phone, row, onEditCallNote, remoteEnabled)) }
            addStatus(
                container = this,
                rows = rows,
                latestCall = latestLocalCall,
                localNotes = localNotes,
                localLoading = localLoading,
                serverLoading = serverLoading,
                remoteEnabled = remoteEnabled,
            )
        })
    }

    private fun latestCallWithoutNote(
        latestCall: PhoneCallRecord?,
        localNotes: List<ContactCallNote>,
    ): PhoneCallRecord? {
        val call = latestCall ?: return null
        val alreadyHasNote = localNotes.any { note ->
            note.callAt == call.startedAt &&
                (note.direction.isBlank() || call.direction.isBlank() || note.direction == call.direction)
        }
        return call.takeUnless { alreadyHasNote }
    }

    private fun titleRow(openFilteredLog: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_system_call_log)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            })
            addView(TextView(activity).apply {
                text = "Бележки и SMS"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(30, 41, 59))
            })
            addView(TextView(activity).apply {
                text = "пълен лог"
                textSize = 13f
                setTextColor(Color.rgb(30, 64, 175))
                setPadding(dp(10), 0, 0, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { openFilteredLog() }
            })
        }
    }

    private fun addLatestCallNoteCard(call: PhoneCallRecord, action: () -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.TRANSPARENT, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            addView(TextView(activity).apply {
                text = "+ Добави бележка към последния разговор"
                textSize = 14.5f
                setTextColor(Color.rgb(30, 64, 175))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    directionLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun historyRow(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        remoteEnabled: Boolean,
    ): LinearLayout {
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val serverConfirmed = isServerConfirmed(phone, row)
        val pendingNote = !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE && row.localNote?.let {
            CallReportNoteOutbox.isCallPending(activity, phone, it)
        } == true
        val colors = when {
            foreignRecord -> Triple(FOREIGN_BACKGROUND, FOREIGN_BORDER, FOREIGN_TEXT)
            row.kind == CallReportHistoryRowKind.NOTE -> Triple(NoteUiStyle.Call.background, NoteUiStyle.Call.border, NoteUiStyle.Call.text)
            else -> Triple(Color.WHITE, Color.rgb(226, 232, 240), Color.rgb(30, 41, 59))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.first, dp(12), colors.second, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            if (!foreignRecord && row.kind == CallReportHistoryRowKind.NOTE && row.localNote != null && row.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val local = row.localNote
                    onEditCallNote(
                        if (remoteEnabled && row.serverNewer) {
                            local.copy(note = row.text, savedAt = maxOf(local.savedAt, row.serverEvent?.updatedAtMs ?: 0L))
                        } else {
                            local
                        },
                    )
                }
            }
            addView(metaView(row, serverConfirmed))
            if (row.text.isNotBlank()) addView(noteText(row.text, colors.third))
            if (pendingNote && !serverConfirmed) addView(pendingSyncText())
            if (!foreignRecord && remoteEnabled && row.serverNewer) addView(serverNewerText())
            if (row.authorIsOtherBroker && row.authorName.isNotBlank()) addView(authorText(row.authorName))
        }
    }

    private fun noteText(value: String, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = 14.5f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, 0)
    }

    private fun pendingSyncText(): TextView {
        val failure = CallReportNoteOutbox.lastFailure(activity)
        return TextView(activity).apply {
            text = if (failure.isBlank()) "Чака сървърна синхронизация" else "Синхронизацията не е потвърдена: $failure"
            textSize = 12f
            setTextColor(if (failure.isBlank()) Color.rgb(100, 116, 139) else Color.rgb(185, 28, 28))
            setPadding(0, dp(6), 0, 0)
        }
    }

    private fun serverNewerText(): TextView = TextView(activity).apply {
        text = "По-нова версия на сървъра"
        textSize = 12f
        setTextColor(Color.rgb(37, 99, 235))
        setPadding(0, dp(6), 0, 0)
    }

    private fun authorText(authorName: String): TextView = TextView(activity).apply {
        text = "Записал: $authorName"
        textSize = 12f
        setTextColor(FOREIGN_TEXT)
        setPadding(0, dp(6), 0, 0)
    }

    private fun isServerConfirmed(phone: String, row: CallReportHistoryRow): Boolean = when (row.kind) {
        CallReportHistoryRowKind.SMS -> row.hasServerCopy || row.localSms?.providerId
            ?.takeIf { it.isNotBlank() }
            ?.let { ServerRecordIndex.isConfirmed(activity, ServerRecordIndex.communicationEventId(activity, "sms", it)) } == true
        CallReportHistoryRowKind.NOTE -> row.hasServerCopy || row.localNote?.let {
            ServerRecordIndex.isCallNoteConfirmed(activity, phone, it)
        } == true
        CallReportHistoryRowKind.PHONE -> false
    }

    private fun metaView(row: CallReportHistoryRow, serverConfirmed: Boolean): TextView {
        val kindText = when (row.kind) {
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE -> "Бележка"
            CallReportHistoryRowKind.PHONE -> "Телефон"
        }
        return TextView(activity).apply {
            text = listOf(kindText, PhoneCallReader.formatStartedAt(row.timeMs), directionLabel(row.direction))
                .filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12.5f
            setTextColor(if (row.authorIsOtherBroker) FOREIGN_TEXT else Color.rgb(71, 85, 105))
            if (serverConfirmed) {
                setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_cloud_note, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun addStatus(
        container: LinearLayout,
        rows: List<CallReportHistoryRow>,
        latestCall: PhoneCallRecord?,
        localNotes: List<ContactCallNote>,
        localLoading: Boolean,
        serverLoading: Boolean,
        remoteEnabled: Boolean,
    ) {
        val text = when {
            localLoading -> "Зареждам SMS и бележки…"
            remoteEnabled && serverLoading -> "Добавям сървърни бележки и SMS…"
            rows.isEmpty() && latestCallWithoutNote(latestCall, localNotes) == null -> "Няма SMS или бележки за този номер"
            else -> ""
        }
        if (text.isNotBlank()) container.addView(status(text))
    }

    private fun status(textValue: String): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13f
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun directionLabel(direction: String): String = when (direction) {
        "in" -> "входящ"
        "out" -> "изходящ"
        else -> ""
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote = ContactCallNote(
        note = "",
        callAt = startedAt,
        savedAt = startedAt,
        direction = direction,
        durationSeconds = durationSeconds,
        clientNoteId = LocalNotesFileStore.clientNoteIdForCall(number, startedAt, direction),
    )

    private companion object {
        val FOREIGN_BACKGROUND: Int = Color.rgb(241, 245, 249)
        val FOREIGN_BORDER: Int = Color.rgb(203, 213, 225)
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
    }
}

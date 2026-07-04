package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Renders the Notes and SMS card; loading and remote state stay in its controller. */
internal class CallReportHistoryRowsUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    private val paginationUi by lazy { CallReportHistoryPaginationUi(activity, dp, roundedRect) }
    private val dayMonthFormatter = SimpleDateFormat("d MMMM", Locale("bg", "BG"))
    private val dayMonthYearFormatter = SimpleDateFormat("d MMMM yyyy", Locale("bg", "BG"))

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
        onEditSms: (SmsMessageRecord, String) -> Unit,
        onPageChanged: () -> Unit,
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
        val companyNames = principal.companies.associate { it.id to it.name }
        val page = paginationUi.currentPage(rows)
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
            val currentWeekSerial = weekStartSerial(System.currentTimeMillis())
            var previousWeekSerial: Long? = null
            page.rows.forEach { row ->
                val rowWeekSerial = weekStartSerial(row.timeMs)
                if (rowWeekSerial != null && rowWeekSerial != previousWeekSerial) {
                    val relativeWeeks = currentWeekSerial?.let { (it - rowWeekSerial) / DAYS_PER_WEEK } ?: 0L
                    addView(weekSeparator(row.timeMs, relativeWeeks))
                    previousWeekSerial = rowWeekSerial
                }
                addView(historyRow(phone, row, onEditCallNote, onEditSms, remoteEnabled, companyNames))
            }
            paginationUi.addNavigation(this, page, onPageChanged)
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            addView(TextView(activity).apply {
                text = "+ Добави"
                textSize = 14.5f
                setTextColor(NoteUiStyle.General.mutedText)
            })
            addView(TextView(activity).apply {
                text = "—"
                textSize = 13f
                setTextColor(Color.rgb(148, 163, 184))
                setPadding(dp(8), 0, dp(8), 0)
            })
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    directionLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(100, 116, 139))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            })
        }
    }

    private fun historyRow(
        phone: String,
        row: CallReportHistoryRow,
        onEditCallNote: (ContactCallNote) -> Unit,
        onEditSms: (SmsMessageRecord, String) -> Unit,
        remoteEnabled: Boolean,
        companyNames: Map<String, String>,
    ): LinearLayout {
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val readOnlyNote = row.kind == CallReportHistoryRowKind.NOTE && !row.editable
        val serverConfirmed = isServerConfirmed(phone, row)
        val localNote = row.localNote
        val pendingGenericSync = !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE && localNote?.let {
            CallReportNoteOutbox.isCallPending(activity, phone, it)
        } == true
        val pendingCompanySync = !foreignRecord && remoteEnabled && row.kind == CallReportHistoryRowKind.NOTE && localNote?.let {
            CallReportTopicNoteOutbox.isCallPending(activity, phone, it.direction, it.callAt)
        } == true
        val pendingCompanyChoice = !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE && localNote?.let {
            CallReportDeferredCompanyAssignmentStore.isCallPending(activity, phone, it.direction, it.callAt)
        } == true
        val colors = when {
            readOnlyNote -> Triple(FOREIGN_BACKGROUND, FOREIGN_BORDER, FOREIGN_TEXT)
            row.kind == CallReportHistoryRowKind.NOTE -> Triple(NoteUiStyle.Call.background, NoteUiStyle.Call.border, NoteUiStyle.Call.text)
            row.kind == CallReportHistoryRowKind.SMS -> Triple(SMS_BACKGROUND, Color.rgb(226, 232, 240), Color.rgb(30, 41, 59))
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
            when {
                row.kind == CallReportHistoryRowKind.NOTE && row.editable -> {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val serverOnly = row.localNote == null && row.serverEvent?.clientEventId?.isNotBlank() == true
                        if (serverOnly) {
                            val event = row.serverEvent ?: return@setOnClickListener
                            activity.startActivity(Intent(activity, ServerNoteEditActivity::class.java).apply {
                                putExtra(ServerNoteEditActivity.EXTRA_PHONE, phone)
                                putExtra(ServerNoteEditActivity.EXTRA_TITLE, event.contactName.ifBlank { phone })
                                putExtra(ServerNoteEditActivity.EXTRA_DIRECTION, row.direction)
                                putExtra(ServerNoteEditActivity.EXTRA_CALL_AT, row.timeMs)
                                putExtra(ServerNoteEditActivity.EXTRA_DURATION, row.durationSeconds)
                                putExtra(ServerNoteEditActivity.EXTRA_SERVER_CLIENT_EVENT_ID, event.clientEventId)
                                putExtra(ServerNoteEditActivity.EXTRA_INITIAL_NOTE_TEXT, row.text)
                            })
                            return@setOnClickListener
                        }
                        val source = row.localNote?.let { localNote ->
                            val serverClientEventId = row.serverEvent?.clientEventId.orEmpty()
                            if (serverClientEventId.isBlank() || localNote.serverClientEventId == serverClientEventId) {
                                localNote
                            } else {
                                localNote.copy(serverClientEventId = serverClientEventId)
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
                                serverClientEventId = row.serverEvent?.clientEventId.orEmpty().ifBlank { source.serverClientEventId },
                            )
                        } else {
                            source.copy(
                                companyId = row.companyId.ifBlank { source.companyId },
                                serverClientEventId = row.serverEvent?.clientEventId.orEmpty().ifBlank { source.serverClientEventId },
                            )
                        }
                        onEditCallNote(editableNote)
                    }
                }
                !foreignRecord && row.kind == CallReportHistoryRowKind.SMS && row.localSms != null -> {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onEditSms(row.localSms, row.companyId) }
                }
            }
            addView(metaView(row, muted = readOnlyNote))
            companyLabel(row.companyId, companyNames, muted = readOnlyNote)?.let(::addView)
            if (row.text.isNotBlank()) addView(noteText(row.text, colors.third))
            when {
                pendingCompanyChoice -> addView(pendingCompanyChoiceText())
                pendingCompanySync -> addView(pendingSyncText(CallReportTopicNoteOutbox.lastFailure(activity)))
                pendingGenericSync && !serverConfirmed -> addView(pendingSyncText(CallReportNoteOutbox.lastFailure(activity)))
            }
            if (readOnlyNote) addView(authorText(row.authorName.ifBlank { "друг потребител" }))
            if (!foreignRecord && remoteEnabled && row.serverNewer) addView(serverNewerText())
        }
    }

    private fun companyLabel(
        companyId: String,
        companyNames: Map<String, String>,
        muted: Boolean = false,
    ): TextView? {
        val id = companyId.trim()
        if (id.isBlank()) return null
        val name = companyNames[id].orEmpty().ifBlank { id }
        return TextView(activity).apply {
            text = name
            textSize = 11.5f
            setTextColor(if (muted) FOREIGN_TEXT else Color.rgb(71, 85, 105))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            activity.getDrawable(R.drawable.ic_cloud_note)?.apply {
                setBounds(0, 0, dp(14), dp(14))
                setCompoundDrawables(this, null, null, null)
                compoundDrawablePadding = dp(4)
            }
            background = roundedRect(
                if (muted) FOREIGN_BADGE_BACKGROUND else Color.rgb(241, 245, 249),
                dp(8),
                Color.TRANSPARENT,
                0,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
    }

    private fun noteText(value: String, color: Int): TextView = TextView(activity).apply {
        text = value
        textSize = 14.5f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, 0)
    }

    private fun weekSeparator(timestampMs: Long, relativeWeeks: Long): TextView {
        return TextView(activity).apply {
            text = "Седмица: ${weekDateRange(timestampMs)} (${relativeWeeksLabel(relativeWeeks)})"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            // Match the blue rounded CRM label used in the contact-history header.
            background = roundedRect(
                activity.getColor(R.color.callreport_icon_background),
                dp(10),
                Color.TRANSPARENT,
                0,
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(6)
            }
        }
    }

    private fun weekDateRange(timestampMs: Long): String {
        val start = weekStartCalendar(timestampMs) ?: return ""
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, DAYS_PER_WEEK.toInt() - 1) }
        return if (start.get(Calendar.YEAR) == end.get(Calendar.YEAR)) {
            "${dayMonthFormatter.format(start.time)} – ${dayMonthYearFormatter.format(end.time)}"
        } else {
            "${dayMonthYearFormatter.format(start.time)} – ${dayMonthYearFormatter.format(end.time)}"
        }
    }

    private fun relativeWeeksLabel(weeks: Long): String = when {
        weeks == 0L -> "преди 0 седмици"
        weeks == 1L -> "преди 1 седмица"
        weeks > 1L -> "преди $weeks седмици"
        weeks == -1L -> "след 1 седмица"
        else -> "след ${-weeks} седмици"
    }

    private fun weekStartSerial(timestampMs: Long): Long? = weekStartCalendar(timestampMs)?.let(::calendarDaySerial)

    private fun weekStartCalendar(timestampMs: Long): Calendar? {
        if (timestampMs <= 0L) return null
        return Calendar.getInstance().apply {
            timeInMillis = timestampMs
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + DAYS_PER_WEEK.toInt()) % DAYS_PER_WEEK.toInt()
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun calendarDaySerial(calendar: Calendar): Long {
        val yearBefore = (calendar.get(Calendar.YEAR) - 1).toLong()
        val daysBeforeYear = 365L * yearBefore + yearBefore / 4L - yearBefore / 100L + yearBefore / 400L
        return daysBeforeYear + calendar.get(Calendar.DAY_OF_YEAR).toLong() - 1L
    }

    private fun pendingCompanyChoiceText(): TextView = TextView(activity).apply {
        text = activity.getString(R.string.dynamic_note_pending_company_choice)
        textSize = 12f
        setTextColor(Color.rgb(146, 64, 14))
        setPadding(0, dp(6), 0, 0)
    }

    private fun pendingSyncText(failure: String): TextView = TextView(activity).apply {
        text = if (failure.isBlank()) {
            activity.getString(R.string.dynamic_note_pending_server_sync)
        } else {
            activity.getString(R.string.dynamic_note_pending_server_sync_failed, failure)
        }
        textSize = 12f
        setTextColor(if (failure.isBlank()) Color.rgb(100, 116, 139) else Color.rgb(185, 28, 28))
        setPadding(0, dp(6), 0, 0)
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

    private fun metaView(row: CallReportHistoryRow, muted: Boolean = false): TextView {
        val kindText = when (row.kind) {
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE -> "Бележка"
            CallReportHistoryRowKind.PHONE -> "Телефон"
        }
        return TextView(activity).apply {
            text = listOf(kindText, PhoneCallReader.formatStartedAt(row.timeMs), directionLabel(row.direction))
                .filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12.5f
            setTextColor(if (muted || row.authorIsOtherBroker) FOREIGN_TEXT else Color.rgb(71, 85, 105))
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
        const val DAYS_PER_WEEK = 7L
        val SMS_BACKGROUND: Int = Color.rgb(248, 250, 252)
        val FOREIGN_BACKGROUND: Int = Color.rgb(241, 245, 249)
        val FOREIGN_BORDER: Int = Color.rgb(203, 213, 225)
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
        val FOREIGN_BADGE_BACKGROUND: Int = Color.rgb(226, 232, 240)
    }
}

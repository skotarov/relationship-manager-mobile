package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Filtered "Пълен лог" for one contact. Source data is loaded and grouped on a
 * background executor once, then only the current page is rendered on the UI thread.
 */
internal class FilteredFullLogController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openCallNoteEditor: (PhoneCallRecord, String, HomeCallNote?) -> Unit,
    private val pageSize: () -> Int,
    private val onStateChanged: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var selectedPhone = ""
    private var loadedPhone = ""
    private var loadedRemoteEnabled: Boolean? = null
    private var loading = false
    private var pageIndex = 0
    private var loadGeneration = 0
    private var entries: List<FullLogEntry> = emptyList()
    private var errorText = ""

    fun invalidate() {
        loadGeneration += 1
        loadedPhone = ""
        loadedRemoteEnabled = null
        loading = false
        pageIndex = 0
        entries = emptyList()
        errorText = ""
    }

    fun previousPage() {
        if (loading || pageIndex <= 0) return
        pageIndex -= 1
        onStateChanged()
    }

    fun nextPage() {
        if (loading || pageIndex >= lastPageIndex()) return
        pageIndex += 1
        onStateChanged()
    }

    fun render(phone: String) {
        if (phone.isBlank()) return
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        val modeChanged = loadedRemoteEnabled != null && loadedRemoteEnabled != remoteEnabled
        if (selectedPhone != phone || modeChanged) {
            selectedPhone = phone
            invalidate()
        }
        if (loadedPhone != phone && !loading) startLoad(phone, remoteEnabled)

        val size = safePageSize()
        val pageCount = pageCount(size)
        pageIndex = pageIndex.coerceIn(0, pageCount - 1)
        val pageEntries = if (loading) {
            emptyList()
        } else {
            entries.drop(pageIndex * size).take(size)
        }

        binding.homeCallsContainer.removeAllViews()
        binding.fullLogProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.paginationContainer.visibility = View.VISIBLE
        binding.previousCallsButton.text = activity.getString(R.string.dynamic_home_previous_calls, size)
        binding.nextCallsButton.text = activity.getString(R.string.dynamic_home_next_calls, size)
        binding.previousCallsButton.isEnabled = !loading && pageIndex > 0
        binding.nextCallsButton.isEnabled = !loading && pageIndex < pageCount - 1
        binding.pageText.text = activity.getString(R.string.dynamic_home_page, pageIndex + 1)
        binding.homeStatusText.text = when {
            loading -> "Зареждам пълния лог…"
            errorText.isNotBlank() -> errorText
            entries.isEmpty() -> if (remoteEnabled) {
                "Няма локални или сървърни записи за този номер"
            } else {
                "Няма локални записи за този номер"
            }
            else -> {
                val first = pageIndex * size + 1
                val last = first + pageEntries.size - 1
                "Пълен лог: $first–$last от ${entries.size}"
            }
        }
        pageEntries.forEach { entry ->
            binding.homeCallsContainer.addView(rowView(phone, entry, remoteEnabled))
        }
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun startLoad(phone: String, remoteEnabled: Boolean) {
        loading = true
        val requestedPhone = phone
        val generation = ++loadGeneration
        executor.execute {
            val result = runCatching {
                val localCalls = PhoneCallReader.callsForPhone(
                    activity,
                    requestedPhone,
                    limit = SOURCE_CALL_LIMIT,
                )
                val localSms = SmsMessageReader.messagesForPhone(
                    activity,
                    requestedPhone,
                    limit = SOURCE_SMS_LIMIT,
                )
                val localNotes = ContactNoteReader.callNotesForPhone(activity, requestedPhone)
                val serverHistory = if (remoteEnabled) {
                    val config = ConfigStore.load(activity)
                    runCatching { CallReportHistoryLookupClient.lookup(config, requestedPhone) }
                        .getOrDefault(CallReportHistoryLookupResult())
                } else {
                    CallReportHistoryLookupResult()
                }
                val merged = CallReportHistoryMerge.merge(
                    context = activity,
                    phone = requestedPhone,
                    principal = serverHistory.principal,
                    localCalls = localCalls,
                    localSms = localSms,
                    localNotes = localNotes,
                    serverEvents = serverHistory.events,
                )
                groupedEntries(merged)
            }
            handler.post {
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    generation != loadGeneration ||
                    requestedPhone != selectedPhone
                ) {
                    return@post
                }
                if (remoteEnabled != CallReportRemoteAccess.isEnabled(activity)) {
                    loading = false
                    loadedPhone = ""
                    loadedRemoteEnabled = null
                    onStateChanged()
                    return@post
                }
                loading = false
                result.onSuccess {
                    entries = it
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = ""
                }.onFailure {
                    entries = emptyList()
                    loadedPhone = requestedPhone
                    loadedRemoteEnabled = remoteEnabled
                    errorText = "Пълният лог не е зареден"
                }
                onStateChanged()
            }
        }
    }

    private fun safePageSize(): Int = pageSize().coerceIn(5, 100)

    private fun pageCount(size: Int): Int = if (entries.isEmpty()) 1 else ((entries.size - 1) / size) + 1

    private fun lastPageIndex(): Int = pageCount(safePageSize()) - 1

    private fun groupedEntries(timeline: List<CallReportHistoryRow>): List<FullLogEntry> {
        val indexedCalls = timeline.mapIndexedNotNull { index, row ->
            index.takeIf { row.kind == CallReportHistoryRowKind.PHONE }
        }
        val notesByCallIndex = mutableMapOf<Int, MutableList<CallReportHistoryRow>>()
        val attachedNoteIndexes = mutableSetOf<Int>()

        timeline.forEachIndexed { noteIndex, note ->
            if (note.kind != CallReportHistoryRowKind.NOTE) return@forEachIndexed
            val targetCallIndex = matchingCallIndex(note, indexedCalls, timeline) ?: return@forEachIndexed
            notesByCallIndex.getOrPut(targetCallIndex) { mutableListOf() }.add(note)
            attachedNoteIndexes += noteIndex
        }

        return timeline.mapIndexedNotNull { index, row ->
            if (row.kind == CallReportHistoryRowKind.NOTE && index in attachedNoteIndexes) {
                null
            } else {
                FullLogEntry(
                    row = row,
                    attachedNotes = notesByCallIndex[index].orEmpty().sortedBy { it.timeMs },
                )
            }
        }
    }

    private fun matchingCallIndex(
        note: CallReportHistoryRow,
        callIndexes: List<Int>,
        timeline: List<CallReportHistoryRow>,
    ): Int? {
        val notePhone = HomeCallPageLoader.noteKey(note.phone)
        var closestIndex: Int? = null
        var closestDelta = Long.MAX_VALUE
        callIndexes.forEach { callIndex ->
            val call = timeline[callIndex]
            if (HomeCallPageLoader.noteKey(call.phone) != notePhone) return@forEach
            if (note.direction.isNotBlank() && call.direction.isNotBlank() && note.direction != call.direction) return@forEach
            val delta = abs(note.timeMs - call.timeMs)
            if (delta <= NOTE_CALL_MATCH_WINDOW_MS && delta < closestDelta) {
                closestIndex = callIndex
                closestDelta = delta
            }
        }
        return closestIndex
    }

    private fun rowView(phone: String, entry: FullLogEntry, remoteEnabled: Boolean): MaterialCardView {
        val row = entry.row
        val foreignRecord = remoteEnabled && row.authorIsOtherBroker
        val localCall = row.localCall
        val localNote = row.localNote
        val editableAttachedNote = if (foreignRecord) {
            null
        } else {
            entry.attachedNotes.firstOrNull { it.localNote != null && it.editable && !it.authorIsOtherBroker }
        }
        val backgroundColor = when {
            foreignRecord -> FOREIGN_BACKGROUND
            row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.background
            else -> activity.getColor(R.color.calllog_surface)
        }
        val borderColor = when {
            foreignRecord -> FOREIGN_BORDER
            row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.border
            else -> activity.getColor(R.color.calllog_border)
        }
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(borderColor)
            setCardBackgroundColor(backgroundColor)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        column.addView(metaView(row, remoteEnabled))
        if (row.text.isNotBlank()) {
            column.addView(TextView(activity).apply {
                text = row.text
                textSize = 14.5f
                setTextColor(
                    if (foreignRecord) {
                        FOREIGN_TEXT
                    } else if (row.kind == CallReportHistoryRowKind.NOTE) {
                        NoteUiStyle.Call.text
                    } else {
                        activity.getColor(R.color.calllog_text)
                    },
                )
                if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            })
        }
        if (remoteEnabled) {
            addServerAuthor(column, row)
            addServerVersionNotice(column, row)
        }

        entry.attachedNotes.forEach { note ->
            column.addView(attachedNoteView(phone, note, remoteEnabled))
        }

        when {
            !foreignRecord && row.kind == CallReportHistoryRowKind.NOTE && localNote != null && row.editable -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openNoteEditor(phone, localNote.withServerClientEventId(row.serverEvent?.clientEventId.orEmpty())) }
            }
            !foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openContactNotes(localCall, localCall.displayName) }
            }
        }

        if (!foreignRecord && row.kind == CallReportHistoryRowKind.PHONE && localCall != null) {
            card.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                column.layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                addView(column)
                addView(noteActionButton(localCall, editableAttachedNote))
            })
        } else {
            card.addView(column)
        }
        return card
    }

    private fun attachedNoteView(phone: String, note: CallReportHistoryRow, remoteEnabled: Boolean): LinearLayout {
        val foreignRecord = remoteEnabled && note.authorIsOtherBroker
        val localNote = note.localNote
        val backgroundColor = if (foreignRecord) FOREIGN_BACKGROUND else NoteUiStyle.Call.background
        val borderColor = if (foreignRecord) FOREIGN_BORDER else NoteUiStyle.Call.border
        val textColor = if (foreignRecord) FOREIGN_TEXT else NoteUiStyle.Call.text
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(backgroundColor, dp(10), borderColor, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            if (!foreignRecord && localNote != null && note.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openNoteEditor(phone, localNote.withServerClientEventId(note.serverEvent?.clientEventId.orEmpty())) }
            }
            addView(metaView(note, remoteEnabled))
            if (note.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = note.text
                    textSize = 14f
                    setTextColor(textColor)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (remoteEnabled) {
                addServerAuthor(this, note)
                addServerVersionNotice(this, note)
            }
        }
    }

    private fun noteActionButton(
        call: PhoneCallRecord,
        editableAttachedNote: CallReportHistoryRow?,
    ): ImageButton {
        return ImageButton(activity).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = if (editableAttachedNote == null) "Добави бележка" else "Редактирай бележката"
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(8) }
            setOnClickListener {
                val existingLocalNote = editableAttachedNote?.localNote?.withServerClientEventId(
                    editableAttachedNote.serverEvent?.clientEventId.orEmpty(),
                )
                if (existingLocalNote != null) {
                    openNoteEditor(call.number, existingLocalNote)
                } else {
                    openCallNoteEditor(call, call.displayName, null)
                }
            }
        }
    }

    private fun openNoteEditor(phone: String, note: ContactCallNote) {
        val displayName = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty()
        openCallNoteEditor(
            PhoneCallRecord(
                number = phone,
                name = displayName,
                direction = note.direction,
                startedAt = note.callAt,
                durationSeconds = note.durationSeconds,
            ),
            displayName,
            HomeCallNote(
                text = note.note,
                updatedAtMs = maxOf(note.savedAt, note.callAt),
                fromServer = note.serverClientEventId.isNotBlank(),
                companyId = note.companyId,
                serverClientEventId = note.serverClientEventId,
            ),
        )
    }

    private fun ContactCallNote.withServerClientEventId(serverClientEventId: String): ContactCallNote {
        val normalized = serverClientEventId.trim()
        return if (normalized.isBlank() || this.serverClientEventId == normalized) {
            this
        } else {
            copy(serverClientEventId = normalized)
        }
    }

    private fun addServerAuthor(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.authorIsOtherBroker || row.authorName.isBlank()) return
        container.addView(TextView(activity).apply {
            text = "Записал: ${row.authorName}"
            textSize = 12f
            setTextColor(FOREIGN_TEXT)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun addServerVersionNotice(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.serverNewer || row.authorIsOtherBroker) return
        container.addView(TextView(activity).apply {
            text = "По-нова версия на бележката е на сървъра"
            textSize = 12f
            setTextColor(Color.rgb(37, 99, 235))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun metaView(row: CallReportHistoryRow, remoteEnabled: Boolean): TextView {
        val type = when (row.kind) {
            CallReportHistoryRowKind.PHONE -> "Телефон"
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE -> "Бележка"
        }
        val direction = when (row.direction) {
            "in" -> "входящ"
            "out" -> "изходящ"
            else -> ""
        }
        val duration = if (row.kind == CallReportHistoryRowKind.PHONE) {
            PhoneCallReader.formatDuration(row.durationSeconds)
        } else {
            ""
        }
        return TextView(activity).apply {
            text = listOf(type, PhoneCallReader.formatStartedAt(row.timeMs), direction, duration)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            textSize = 12.5f
            setTextColor(if (row.authorIsOtherBroker) FOREIGN_TEXT else Color.rgb(71, 85, 105))
            setTypeface(typeface, Typeface.BOLD)
            val leftIcon = if (row.kind == CallReportHistoryRowKind.PHONE) callStatusIcon(row) else 0
            val rightIcon = if (remoteEnabled && row.hasServerCopy) R.drawable.ic_cloud_note else 0
            if (leftIcon != 0 || rightIcon != 0) {
                setCompoundDrawablesWithIntrinsicBounds(leftIcon, 0, rightIcon, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun callStatusIcon(row: CallReportHistoryRow): Int = when {
        row.status == "rejected" || row.status == "blocked" -> R.drawable.ic_call_rejected
        row.status == "missed" -> R.drawable.ic_call_missed
        row.direction == "out" && row.durationSeconds <= 0L -> R.drawable.ic_call_rejected
        row.direction != "out" && row.durationSeconds <= 0L -> R.drawable.ic_call_missed
        row.direction == "out" -> R.drawable.ic_call_outgoing
        else -> R.drawable.ic_call_incoming
    }

    private data class FullLogEntry(
        val row: CallReportHistoryRow,
        val attachedNotes: List<CallReportHistoryRow> = emptyList(),
    )

    private companion object {
        const val SOURCE_CALL_LIMIT = 200
        const val SOURCE_SMS_LIMIT = 100
        const val NOTE_CALL_MATCH_WINDOW_MS = 90_000L
        val FOREIGN_BACKGROUND: Int = Color.rgb(241, 245, 249)
        val FOREIGN_BORDER: Int = Color.rgb(203, 213, 225)
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
    }
}

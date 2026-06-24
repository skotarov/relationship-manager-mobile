package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.onlineimoti.calllog.databinding.ActivityHomeBinding
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Used only when Home is opened with a phone filter from "Пълен лог".
 * It joins the phone's local Android log with the server timeline while the normal Home
 * screen remains a strictly local Call Log.
 *
 * Call notes are rendered inside their matching phone-call card, not as independent
 * timeline rows. A note without a matching phone call remains visible as a standalone card.
 */
internal class FilteredFullLogController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val openContactNotes: (PhoneCallRecord, String) -> Unit,
    private val openCallNoteEditor: (PhoneCallRecord, String) -> Unit,
    private val onLoaded: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var requestedPhone = ""
    private var loadedPhone = ""
    private var loading = false
    private var rows: List<CallReportHistoryRow> = emptyList()
    private var errorText = ""

    fun invalidate() {
        loadedPhone = ""
    }

    fun render(phone: String) {
        if (phone.isBlank()) return
        if (requestedPhone != phone) {
            requestedPhone = phone
            loadedPhone = ""
            rows = emptyList()
            errorText = ""
        }
        if (loadedPhone != phone && !loading) load(phone)

        binding.homeCallsContainer.removeAllViews()
        binding.paginationContainer.visibility = View.GONE
        binding.homeStatusText.text = when {
            loading -> "Зареждам пълния лог…"
            errorText.isNotBlank() -> errorText
            rows.isEmpty() -> "Няма локални или сървърни записи за този номер"
            else -> "Пълен лог: локални и сървърни записи"
        }
        groupedEntries(rows).forEach { entry ->
            binding.homeCallsContainer.addView(rowView(phone, entry))
        }
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun load(phone: String) {
        loading = true
        val requested = phone
        executor.execute {
            val result = runCatching {
                val localCalls = PhoneCallReader.callsForPhone(activity, requested, limit = 500)
                val localSms = SmsMessageReader.messagesForPhone(activity, requested, limit = 150)
                val localNotes = ContactNoteReader.callNotesForPhone(activity, requested)
                val config = ConfigStore.load(activity)
                val serverHistory = runCatching {
                    CallReportHistoryLookupClient.lookup(config, requested)
                }.getOrDefault(CallReportHistoryLookupResult())
                CallReportHistoryMerge.merge(
                    context = activity,
                    phone = requested,
                    principal = serverHistory.principal,
                    localCalls = localCalls,
                    localSms = localSms,
                    localNotes = localNotes,
                    serverEvents = serverHistory.events,
                )
            }
            handler.post {
                if (activity.isFinishing || activity.isDestroyed || requested != requestedPhone) return@post
                loading = false
                result.onSuccess {
                    rows = it
                    loadedPhone = requested
                    errorText = ""
                }.onFailure {
                    rows = emptyList()
                    loadedPhone = requested
                    errorText = "Пълният лог не е зареден"
                }
                onLoaded()
            }
        }
    }

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
                    attachedNotes = notesByCallIndex[index]
                        .orEmpty()
                        .sortedBy { it.timeMs },
                )
            }
        }
    }

    /**
     * Local notes retain the original call timestamp. Canonical server notes use the
     * same occurred_at timestamp. A short tolerance covers provider timestamp rounding.
     */
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

    private fun rowView(phone: String, entry: FullLogEntry): MaterialCardView {
        val row = entry.row
        val foreignNote = isForeignNote(row)
        val background = when {
            foreignNote -> Color.rgb(248, 250, 252)
            row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.background
            else -> activity.getColor(R.color.calllog_surface)
        }
        val border = when {
            foreignNote -> Color.rgb(203, 213, 225)
            row.kind == CallReportHistoryRowKind.NOTE -> NoteUiStyle.Call.border
            else -> activity.getColor(R.color.calllog_border)
        }
        val card = MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(border)
            setCardBackgroundColor(background)
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

        column.addView(metaView(row))
        if (row.text.isNotBlank()) {
            column.addView(TextView(activity).apply {
                text = row.text
                textSize = 14.5f
                setTextColor(if (row.kind == CallReportHistoryRowKind.NOTE) NoteUiStyle.Call.text else activity.getColor(R.color.calllog_text))
                if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(5), 0, 0)
            })
        }
        addServerAuthor(column, row)
        addServerVersionNotice(column, row)
        addReadOnlyNotice(column, row)

        entry.attachedNotes.forEach { note ->
            column.addView(attachedNoteView(phone, note))
        }

        when {
            row.kind == CallReportHistoryRowKind.NOTE && row.localNote != null && row.editable -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openNoteEditor(phone, row.localNote) }
            }
            row.kind == CallReportHistoryRowKind.PHONE && row.localCall != null -> {
                val editableAttachedNote = entry.attachedNotes.firstOrNull { it.localNote != null && it.editable }
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener { openContactNotes(row.localCall, row.localCall.displayName) }
                column.addView(callNoteAction(row.localCall, editableAttachedNote))
            }
        }

        card.addView(column)
        return card
    }

    private fun attachedNoteView(phone: String, note: CallReportHistoryRow): LinearLayout {
        val foreignNote = isForeignNote(note)
        val background = if (foreignNote) Color.rgb(248, 250, 252) else NoteUiStyle.Call.background
        val border = if (foreignNote) Color.rgb(203, 213, 225) else NoteUiStyle.Call.border
        val textColor = if (foreignNote) Color.rgb(71, 85, 105) else NoteUiStyle.Call.text
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(background, dp(10), border, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            if (note.localNote != null && note.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener { openNoteEditor(phone, note.localNote) }
            }
            addView(metaView(note))
            if (note.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = note.text
                    textSize = 14f
                    setTextColor(textColor)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                })
            }
            addServerAuthor(this, note)
            addServerVersionNotice(this, note)
            addReadOnlyNotice(this, note)
        }
    }

    private fun callNoteAction(
        call: PhoneCallRecord,
        editableAttachedNote: CallReportHistoryRow?,
    ): TextView {
        return TextView(activity).apply {
            text = if (editableAttachedNote == null) "+ Добави бележка" else "Редактирай бележката"
            textSize = 12.5f
            setTextColor(Color.rgb(30, 64, 175))
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                editableAttachedNote?.localNote?.let { openNoteEditor(call.number, it) }
                    ?: openCallNoteEditor(call, call.displayName)
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
        )
    }

    private fun addServerAuthor(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.hasServerCopy || row.authorName.isBlank()) return
        container.addView(TextView(activity).apply {
            text = "Записал: ${row.authorName}"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun addServerVersionNotice(container: LinearLayout, row: CallReportHistoryRow) {
        if (!row.serverNewer) return
        container.addView(TextView(activity).apply {
            text = "По-нова версия на бележката е на сървъра"
            textSize = 12f
            setTextColor(Color.rgb(37, 99, 235))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun addReadOnlyNotice(container: LinearLayout, row: CallReportHistoryRow) {
        if (!isForeignNote(row)) return
        container.addView(TextView(activity).apply {
            text = "Бележка от ${row.authorName} · само за преглед"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun isForeignNote(row: CallReportHistoryRow): Boolean {
        return row.kind == CallReportHistoryRowKind.NOTE && row.authorName.isNotBlank() && !row.editable
    }

    private fun metaView(row: CallReportHistoryRow): TextView {
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
                .filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12.5f
            setTextColor(Color.rgb(71, 85, 105))
            setTypeface(typeface, Typeface.BOLD)
            val leftIcon = if (row.kind == CallReportHistoryRowKind.PHONE) callStatusIcon(row) else 0
            val rightIcon = if (row.hasServerCopy) R.drawable.ic_cloud_note else 0
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
        const val NOTE_CALL_MATCH_WINDOW_MS = 90_000L
    }
}

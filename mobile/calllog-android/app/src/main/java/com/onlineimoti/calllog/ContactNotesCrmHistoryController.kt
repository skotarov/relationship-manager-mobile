package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

internal class ContactNotesCrmHistoryController(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val serverDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var started = false
    private var skippedReason = ""
    private var loading = false
    private var error = false
    private var serverNotes: List<CrmServerNote> = emptyList()

    fun loadOnce(phone: String) {
        if (started) return
        started = true
        if (phone.isBlank()) {
            skippedReason = "Няма телефон за CRM проверка"
            return
        }
        val config = ConfigStore.load(activity)
        if (!config.remoteEnabled || config.baseUrl.isBlank()) {
            skippedReason = "CRM връзката е изключена от Server настройките"
            return
        }
        loading = true
        error = false
        rerender()
        executor.execute {
            val result = runCatching { CrmContactHistoryClient.fetch(config, phone) }
            handler.post {
                loading = false
                result.onSuccess {
                    serverNotes = it.serverNotes
                    error = false
                    skippedReason = ""
                }.onFailure {
                    serverNotes = emptyList()
                    error = true
                    skippedReason = ""
                }
                rerender()
            }
        }
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    fun addSection(root: LinearLayout, phone: String, onEditCallNote: (ContactCallNote) -> Unit) {
        val localCalls = PhoneCallReader.callsForPhone(activity, phone, limit = 100)
        val localNotes = ContactNoteReader.callNotesForPhone(activity, phone)
        val latestCallWithoutNote = latestCallWithoutNote(localCalls, localNotes)
        val hiddenCallsWithoutNotes = localCalls.count { call -> !hasNoteForCall(call, localNotes) } - if (latestCallWithoutNote != null) 1 else 0
        val timeline = buildTimeline(localNotes, latestCallWithoutNote)

        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }

            addView(headerUi.sectionTitleWithDrawable("Хронология", R.drawable.ic_system_call_log))
            timeline.forEach { item -> addTimelineCard(item, onEditCallNote) }
            addStatusIfNeeded(this, timeline, hiddenCallsWithoutNotes.coerceAtLeast(0))
        })
    }

    private fun buildTimeline(localNotes: List<ContactCallNote>, latestCallWithoutNote: PhoneCallRecord?): List<TimelineItem> {
        val items = mutableListOf<TimelineItem>()
        val localClientIds = localNotes.map { it.clientNoteId }.filter { it.isNotBlank() }.toSet()
        latestCallWithoutNote?.let { call -> items.add(TimelineItem.LatestCallAction(call)) }
        localNotes.forEach { note -> items.add(TimelineItem.LocalNote(note)) }
        serverNotes
            .filterNot { note -> note.clientNoteId.isNotBlank() && localClientIds.contains(note.clientNoteId) }
            .forEach { note -> items.add(TimelineItem.ServerNote(note, serverTime(note))) }
        return items.sortedByDescending { it.timeMs }
    }

    private fun LinearLayout.addTimelineCard(item: TimelineItem, onEditCallNote: (ContactCallNote) -> Unit) {
        when (item) {
            is TimelineItem.LatestCallAction -> addView(latestCallActionCard(item.call, onEditCallNote))
            is TimelineItem.LocalNote -> addView(localNoteCard(item.note, onEditCallNote))
            is TimelineItem.ServerNote -> addView(serverNoteCard(item.note))
        }
    }

    private fun addStatusIfNeeded(container: LinearLayout, timeline: List<TimelineItem>, hiddenCallsWithoutNotes: Int) {
        when {
            loading -> container.addView(statusText("Зареждам CRM история…"))
            error -> container.addView(statusText("CRM историята не е заредена"))
            skippedReason.isNotBlank() -> container.addView(statusText(skippedReason))
            timeline.isEmpty() && hiddenCallsWithoutNotes <= 0 -> container.addView(statusText("Няма бележки или CRM записи за този номер"))
            serverNotes.isEmpty() -> container.addView(statusText("Няма CRM записи от сървъра за този номер"))
        }
        if (hiddenCallsWithoutNotes > 0) {
            container.addView(statusText("Скрити са $hiddenCallsWithoutNotes разговора без бележка. Всички позвънявания се виждат на началния екран."))
        }
    }

    private fun latestCallActionCard(call: PhoneCallRecord, onEditCallNote: (ContactCallNote) -> Unit): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(255, 255, 255), dp(12), Color.rgb(226, 232, 240), dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onEditCallNote(call.toContactCallNote()) }
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = "+ Добави към последния разговор"
                textSize = 14.5f
                setTextColor(NoteUiStyle.Call.mutedText)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(call.startedAt),
                    headerUi.directionArrowLabel(call.direction),
                    PhoneCallReader.formatDuration(call.durationSeconds),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun localNoteCard(note: ContactCallNote, onEditCallNote: (ContactCallNote) -> Unit): LinearLayout {
        val colors = NoteUiStyle.Call
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(colors.background, dp(12), colors.border, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { onEditCallNote(note) }
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    headerUi.directionArrowLabel(note.direction),
                    PhoneCallReader.formatDuration(note.durationSeconds),
                    "локална бележка"
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(colors.metaText)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = note.note
                textSize = 14.5f
                setTextColor(colors.text)
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun serverNoteCard(note: CrmServerNote): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
            layoutParams = cardLayoutParams()
            addView(TextView(activity).apply {
                text = metaText(note)
                textSize = 12.5f
                setTextColor(Color.rgb(71, 85, 105))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = note.text
                textSize = 14.5f
                setTextColor(Color.rgb(51, 65, 85))
                setPadding(0, dp(5), 0, 0)
            })
            if (note.propertyTitle.isRealValue()) {
                addView(TextView(activity).apply {
                    text = "Обява: ${note.propertyTitle}"
                    textSize = 12.5f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun statusText(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }
    }

    private fun latestCallWithoutNote(localCalls: List<PhoneCallRecord>, localNotes: List<ContactCallNote>): PhoneCallRecord? {
        return localCalls.firstOrNull { call -> !hasNoteForCall(call, localNotes) }
    }

    private fun hasNoteForCall(call: PhoneCallRecord, localNotes: List<ContactCallNote>): Boolean {
        return localNotes.any { note ->
            note.callAt == call.startedAt && (note.direction.isBlank() || call.direction.isBlank() || note.direction == call.direction)
        }
    }

    private fun metaText(note: CrmServerNote): String {
        val author = note.authorName.ifBlank { note.authorLogin }.ifBlank { note.authorId }
        return listOf("CRM", author, note.createdAt).filter { it.isNotBlank() }.joinToString(" • ")
    }

    private fun serverTime(note: CrmServerNote): Long {
        val value = note.createdAt.ifBlank { note.updatedAt }
        return runCatching { serverDateFormat.parse(value)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote {
        return ContactCallNote(
            note = "",
            callAt = startedAt,
            savedAt = startedAt,
            direction = direction,
            durationSeconds = durationSeconds,
            clientNoteId = LocalNotesFileStore.clientNoteIdForCall(number, startedAt, direction),
        )
    }

    private fun String.isRealValue(): Boolean {
        val value = trim()
        return value.isNotBlank() && !value.equals("null", ignoreCase = true)
    }

    private sealed class TimelineItem(val timeMs: Long) {
        class LatestCallAction(val call: PhoneCallRecord) : TimelineItem(call.startedAt)
        class LocalNote(val note: ContactCallNote) : TimelineItem(note.callAt.takeIf { it > 0L } ?: note.savedAt)
        class ServerNote(val note: CrmServerNote, timeMs: Long) : TimelineItem(timeMs)
    }
}

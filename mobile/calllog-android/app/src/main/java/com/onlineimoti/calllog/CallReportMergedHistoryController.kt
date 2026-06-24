package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Shows only notes and SMS for one contact; phone call rows stay on the Home Call Log screen. */
internal class CallReportMergedHistoryController(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
    private val rerender: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)

    private var started = false
    private var localLoading = false
    private var serverLoading = false
    private var latestLocalCall: PhoneCallRecord? = null
    private var localSms: List<SmsMessageRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var serverHistory = CallReportHistoryLookupResult()
    private var loadError = ""

    fun loadOnce(phone: String) {
        if (started || phone.isBlank()) return
        started = true
        refreshLocal(phone)
        refreshServer(phone)
    }

    fun refreshServer(phone: String) {
        if (phone.isBlank() || serverLoading) return
        val config = ConfigStore.load(activity)
        if (!config.remoteEnabled || config.baseUrl.isBlank() || config.accessToken.isBlank()) return
        serverLoading = true
        executor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, phone) }
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                serverLoading = false
                result.onSuccess {
                    serverHistory = it
                    loadError = ""
                    ServerRecordIndex.markConfirmed(activity, it.events.map { event -> event.clientEventId })
                }.onFailure {
                    loadError = "Сървърните бележки и SMS не са заредени"
                }
                rerender()
            }
        }
    }

    fun refreshLocal(phone: String) {
        if (phone.isBlank() || localLoading) return
        localLoading = true
        executor.execute {
            val result = runCatching {
                LocalSnapshot(
                    latestCall = PhoneCallReader.callsForPhone(activity, phone, limit = 1).firstOrNull(),
                    sms = SmsMessageReader.messagesForPhone(activity, phone, limit = 150),
                    notes = ContactNoteReader.callNotesForPhone(activity, phone),
                )
            }.getOrDefault(LocalSnapshot())
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                latestLocalCall = result.latestCall
                localSms = result.sms
                localNotes = result.notes
                localLoading = false
                rerender()
            }
        }
    }

    fun addSection(
        root: LinearLayout,
        phone: String,
        openFilteredLog: () -> Unit,
        onEditCallNote: (ContactCallNote) -> Unit,
    ) {
        val historyEvents = serverHistory.events.filter { event ->
            event.communicationType.equals("sms", ignoreCase = true) ||
                event.communicationType.equals("note", ignoreCase = true)
        }
        val rows = CallReportHistoryMerge.merge(
            context = activity,
            phone = phone,
            principal = serverHistory.principal,
            localCalls = emptyList(),
            localSms = localSms,
            localNotes = localNotes,
            serverEvents = historyEvents,
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
            latestCallWithoutNote()?.let { call ->
                addView(addLatestCallNoteCard(call) { onEditCallNote(call.toContactCallNote()) })
            }
            rows.forEach { row -> addView(historyRow(phone, row, onEditCallNote)) }
            addStatus(this, rows)
        })
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun latestCallWithoutNote(): PhoneCallRecord? {
        val call = latestLocalCall ?: return null
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
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
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
    ): LinearLayout {
        val foreignNote = row.kind == CallReportHistoryRowKind.NOTE && row.authorName.isNotBlank() && !row.editable
        val serverConfirmed = isServerConfirmed(phone, row)
        val pendingNote = row.kind == CallReportHistoryRowKind.NOTE && row.localNote?.let {
            CallReportNoteOutbox.isCallPending(activity, phone, it)
        } == true
        val colors = when {
            foreignNote -> Triple(Color.rgb(248, 250, 252), Color.rgb(203, 213, 225), Color.rgb(71, 85, 105))
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
            if (row.kind == CallReportHistoryRowKind.NOTE && row.localNote != null && row.editable) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val local = row.localNote
                    onEditCallNote(
                        if (row.serverNewer) local.copy(
                            note = row.text,
                            savedAt = maxOf(local.savedAt, row.serverEvent?.updatedAtMs ?: 0L),
                        ) else local,
                    )
                }
            }
            addView(metaView(row, serverConfirmed))
            if (row.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.text
                    textSize = 14.5f
                    setTextColor(colors.third)
                    if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(5), 0, 0)
                })
            }
            if (pendingNote && !serverConfirmed) {
                val failure = CallReportNoteOutbox.lastFailure(activity)
                addView(TextView(activity).apply {
                    text = if (failure.isBlank()) "Чака сървърна синхронизация" else "Синхронизацията не е потвърдена: $failure"
                    textSize = 12f
                    setTextColor(if (failure.isBlank()) Color.rgb(100, 116, 139) else Color.rgb(185, 28, 28))
                    setPadding(0, dp(6), 0, 0)
                })
            }
            if (row.serverNewer) {
                addView(TextView(activity).apply {
                    text = "По-нова версия на сървъра"
                    textSize = 12f
                    setTextColor(Color.rgb(37, 99, 235))
                    setPadding(0, dp(6), 0, 0)
                })
            }
            if (foreignNote) {
                addView(TextView(activity).apply {
                    text = "Бележка от ${row.authorName} · само за преглед"
                    textSize = 12f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
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
            setTextColor(Color.rgb(71, 85, 105))
            setTypeface(typeface, Typeface.BOLD)
            if (serverConfirmed) {
                setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_cloud_note, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun directionLabel(direction: String): String = when (direction) {
        "in" -> "входящ"
        "out" -> "изходящ"
        else -> ""
    }

    private fun addStatus(container: LinearLayout, rows: List<CallReportHistoryRow>) {
        when {
            localLoading -> container.addView(status("Зареждам SMS и бележки…"))
            serverLoading -> container.addView(status("Добавям сървърни бележки и SMS…"))
            loadError.isNotBlank() -> container.addView(status(loadError))
            rows.isEmpty() && latestCallWithoutNote() == null -> container.addView(status("Няма SMS или бележки за този номер"))
        }
    }

    private fun status(textValue: String): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13f
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun PhoneCallRecord.toContactCallNote(): ContactCallNote = ContactCallNote(
        note = "",
        callAt = startedAt,
        savedAt = startedAt,
        direction = direction,
        durationSeconds = durationSeconds,
        clientNoteId = LocalNotesFileStore.clientNoteIdForCall(number, startedAt, direction),
    )

    private data class LocalSnapshot(
        val latestCall: PhoneCallRecord? = null,
        val sms: List<SmsMessageRecord> = emptyList(),
        val notes: List<ContactCallNote> = emptyList(),
    )
}

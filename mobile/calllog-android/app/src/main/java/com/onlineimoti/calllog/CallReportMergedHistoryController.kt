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
        if (!CallReportRemoteAccess.isEnabled(config)) {
            val hadServerState = serverLoading || serverHistory.events.isNotEmpty() ||
                serverHistory.principal != CallReportHistoryPrincipal() || loadError.isNotBlank()
            serverLoading = false
            serverHistory = CallReportHistoryLookupResult()
            loadError = ""
            if (hadServerState) rerender()
            return
        }
        if (!CallReportRemoteAccess.isReady(config)) return
        serverLoading = true
        executor.execute {
            val result = runCatching { CallReportHistoryLookupClient.lookup(config, phone) }
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (!CallReportRemoteAccess.isEnabled(activity)) {
                    serverLoading = false
                    serverHistory = CallReportHistoryLookupResult()
                    loadError = ""
                    rerender()
                    return@post
                }
                serverLoading = false
                result.onSuccess {
                    serverHistory = it
                    loadError = ""
                    ServerRecordIndex.markConfirmed(activity, it.events.map { event -> event.clientEventId })
                }.onFailure { error ->
                    loadError = serverErrorText(error)
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
        val remoteEnabled = CallReportRemoteAccess.isEnabled(activity)
        addServerErrorBelowContactName(root, remoteEnabled)
        val historyEvents = if (remoteEnabled) {
            serverHistory.events.filter { event ->
                event.communicationType.equals("sms", ignoreCase = true) ||
                    event.communicationType.equals("note", ignoreCase = true)
            }
        } else {
            emptyList()
        }
        val rows = CallReportHistoryMerge.merge(
            context = activity,
            phone = phone,
            principal = if (remoteEnabled) serverHistory.principal else CallReportHistoryPrincipal(),
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
            rows.forEach { row -> addView(historyRow(phone, row, onEditCallNote, remoteEnabled)) }
            addStatus(this, rows, remoteEnabled)
        })
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun addServerErrorBelowContactName(root: LinearLayout, remoteEnabled: Boolean) {
        if (!remoteEnabled || loadError.isBlank()) return
        root.addView(TextView(activity).apply {
            text = loadError
            textSize = 12.5f
            setTextColor(Color.rgb(185, 28, 28))
            setPadding(dp(2), 0, dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }, minOf(1, root.childCount))
    }

    private fun serverErrorText(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        val httpStatus = Regex("\\bHTTP\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (httpStatus != null) {
            return when (httpStatus) {
                400 -> "Сървър: невалидна заявка (400)"
                401 -> "Сървър: невалиден access token (401)"
                403 -> "Сървър: достъпът е отказан (403)"
                404 -> "Сървър: history_lookup.php не е намерен (404)"
                408 -> "Сървър: изтече времето за изчакване (408)"
                429 -> "Сървър: твърде много заявки (429)"
                in 500..599 -> "Сървър: вътрешна грешка ($httpStatus)"
                else -> "Сървър: HTTP $httpStatus"
            }
        }

        val rootCause = rootCause(error)
        return when (rootCause) {
            is java.net.UnknownHostException -> "Сървър: адресът не е открит"
            is java.net.ConnectException -> "Сървър: няма връзка със сървъра"
            is java.net.SocketTimeoutException -> "Сървър: изтече времето за изчакване"
            is org.json.JSONException -> "Сървър: невалиден JSON отговор"
            else -> {
                val safeMessage = message.replace(Regex("\\s+"), " ").take(120)
                if (safeMessage.isBlank() || safeMessage.equals("History lookup failed", ignoreCase = true)) {
                    "Сървър: неуспешно зареждане на историята"
                } else {
                    "Сървър: $safeMessage"
                }
            }
        }
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
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
                            local.copy(
                                note = row.text,
                                savedAt = maxOf(local.savedAt, row.serverEvent?.updatedAtMs ?: 0L),
                            )
                        } else {
                            local
                        },
                    )
                }
            }
            addView(metaView(row, serverConfirmed))
            if (row.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.text
                    textSize = 14.5f
                    setTextColor(colors.third)
                    setTypeface(typeface, Typeface.BOLD)
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
            if (!foreignRecord && remoteEnabled && row.serverNewer) {
                addView(TextView(activity).apply {
                    text = "По-нова версия на сървъра"
                    textSize = 12f
                    setTextColor(Color.rgb(37, 99, 235))
                    setPadding(0, dp(6), 0, 0)
                })
            }
            addServerAuthor(this, row)
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
            setTypeface(typeface, Typeface.NORMAL)
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

    private fun addStatus(container: LinearLayout, rows: List<CallReportHistoryRow>, remoteEnabled: Boolean) {
        when {
            localLoading -> container.addView(status("Зареждам SMS и бележки…"))
            remoteEnabled && serverLoading -> container.addView(status("Добавям сървърни бележки и SMS…"))
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

    private companion object {
        val FOREIGN_BACKGROUND: Int = Color.rgb(241, 245, 249)
        val FOREIGN_BORDER: Int = Color.rgb(203, 213, 225)
        val FOREIGN_TEXT: Int = Color.rgb(100, 116, 139)
    }
}

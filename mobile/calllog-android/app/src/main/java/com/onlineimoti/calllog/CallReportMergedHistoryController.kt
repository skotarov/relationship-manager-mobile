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

/** Renders one combined local/server timeline for a contact. */
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
    private var localCalls: List<PhoneCallRecord> = emptyList()
    private var localSms: List<SmsMessageRecord> = emptyList()
    private var localNotes: List<ContactCallNote> = emptyList()
    private var serverHistory = CallReportHistoryLookupResult()
    private var loadError = ""

    fun loadOnce(phone: String) {
        if (started || phone.isBlank()) return
        started = true
        refreshLocal(phone)
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
                    loadError = "Сървърната история не е заредена"
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
                    calls = PhoneCallReader.callsForPhone(activity, phone, limit = 100),
                    sms = SmsMessageReader.messagesForPhone(activity, phone, limit = 150),
                    notes = ContactNoteReader.callNotesForPhone(activity, phone),
                )
            }.getOrDefault(LocalSnapshot())
            handler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                localCalls = result.calls
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
        val rows = CallReportHistoryMerge.merge(
            context = activity,
            phone = phone,
            principal = serverHistory.principal,
            localCalls = localCalls,
            localSms = localSms,
            localNotes = localNotes,
            serverEvents = serverHistory.events,
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
            rows.forEach { row -> addView(historyRow(row, onEditCallNote)) }
            addStatus(this, rows)
        })
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
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
                text = "Хронология"
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

    private fun historyRow(row: CallReportHistoryRow, onEditCallNote: (ContactCallNote) -> Unit): LinearLayout {
        val foreignNote = row.kind == CallReportHistoryRowKind.NOTE && row.authorName.isNotBlank() && !row.editable
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
            addView(metaView(row))
            if (row.text.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.text
                    textSize = 14.5f
                    setTextColor(colors.third)
                    if (row.kind == CallReportHistoryRowKind.NOTE) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(5), 0, 0)
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

    private fun metaView(row: CallReportHistoryRow): TextView {
        val kindText = when (row.kind) {
            CallReportHistoryRowKind.PHONE -> "Телефон"
            CallReportHistoryRowKind.SMS -> "SMS"
            CallReportHistoryRowKind.NOTE -> if (row.isServerOnly) "Сървърна бележка" else "Бележка"
        }
        val direction = when (row.direction) {
            "in" -> "входящ"
            "out" -> "изходящ"
            else -> ""
        }
        val duration = if (row.kind == CallReportHistoryRowKind.PHONE) PhoneCallReader.formatDuration(row.durationSeconds) else ""
        val status = row.status.ifBlank { "" }
        return TextView(activity).apply {
            text = listOf(kindText, PhoneCallReader.formatStartedAt(row.timeMs), direction, duration, status)
                .filter { it.isNotBlank() }.joinToString(" • ")
            textSize = 12.5f
            setTextColor(Color.rgb(71, 85, 105))
            setTypeface(typeface, Typeface.BOLD)
            if (row.hasServerCopy) {
                setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_cloud_note, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    private fun addStatus(container: LinearLayout, rows: List<CallReportHistoryRow>) {
        when {
            localLoading -> container.addView(status("Зареждам локалната история…"))
            serverLoading -> container.addView(status("Добавям сървърната история…"))
            loadError.isNotBlank() -> container.addView(status(loadError))
            rows.isEmpty() -> container.addView(status("Няма разговори, SMS или бележки за този номер"))
        }
    }

    private fun status(textValue: String): TextView = TextView(activity).apply {
        text = textValue
        textSize = 13f
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private data class LocalSnapshot(
        val calls: List<PhoneCallRecord> = emptyList(),
        val sms: List<SmsMessageRecord> = emptyList(),
        val notes: List<ContactCallNote> = emptyList(),
    )
}

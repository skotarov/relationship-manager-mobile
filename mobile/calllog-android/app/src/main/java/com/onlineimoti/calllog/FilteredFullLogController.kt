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

/**
 * Used only when Home is opened with a phone filter from "Пълен лог".
 * It joins the phone's local Android log with the server timeline while the normal Home
 * screen remains a strictly local Call Log.
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
        rows.forEach { row ->
            binding.homeCallsContainer.addView(rowView(phone, row))
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

    private fun rowView(phone: String, row: CallReportHistoryRow): MaterialCardView {
        val foreignNote = row.kind == CallReportHistoryRowKind.NOTE && row.authorName.isNotBlank() && !row.editable
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
        if (row.hasServerCopy && row.authorName.isNotBlank()) {
            column.addView(TextView(activity).apply {
                text = "Записал: ${row.authorName}"
                textSize = 12f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(6), 0, 0)
            })
        }
        if (row.serverNewer) {
            column.addView(TextView(activity).apply {
                text = "По-нова версия на бележката е на сървъра"
                textSize = 12f
                setTextColor(Color.rgb(37, 99, 235))
                setPadding(0, dp(6), 0, 0)
            })
        }
        if (foreignNote) {
            column.addView(TextView(activity).apply {
                text = "Бележка от ${row.authorName} · само за преглед"
                textSize = 12f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(6), 0, 0)
            })
        }

        val localCall = row.localCall
        val localNote = row.localNote
        when {
            row.kind == CallReportHistoryRowKind.NOTE && localNote != null && row.editable -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    openCallNoteEditor(
                        PhoneCallRecord(
                            number = phone,
                            name = ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty(),
                            direction = localNote.direction,
                            startedAt = localNote.callAt,
                            durationSeconds = localNote.durationSeconds,
                        ),
                        ContactGroupFilter.resolveDisplayName(activity, phone).orEmpty(),
                    )
                }
            }
            row.kind == CallReportHistoryRowKind.PHONE && localCall != null -> {
                card.isClickable = true
                card.isFocusable = true
                card.setOnClickListener {
                    openContactNotes(localCall, localCall.displayName)
                }
                column.addView(TextView(activity).apply {
                    text = "+ Добави/отвори бележка"
                    textSize = 12.5f
                    setTextColor(Color.rgb(30, 64, 175))
                    setPadding(0, dp(7), 0, 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        openCallNoteEditor(localCall, localCall.displayName)
                    }
                })
            }
        }

        card.addView(column)
        return card
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
            if (row.hasServerCopy) {
                setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_cloud_note, 0)
                compoundDrawablePadding = dp(6)
            }
        }
    }
}

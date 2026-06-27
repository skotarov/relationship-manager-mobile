package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

internal class PostCallLookupPopup(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val phone: () -> String,
    private val title: () -> String,
    private val setWindowManager: (WindowManager) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long, () -> Unit) -> Unit,
    private val showNoteEditor: () -> Unit,
    private val showBubbleAfterLookup: () -> Unit,
    private val timeoutMs: Long,
) {
    private val handler = Handler(Looper.getMainLooper())
    /** Invalidates late lookup responses after the overlay was replaced or timed out. */
    private var activeRequestId = 0L

    fun show() {
        val requestId = ++activeRequestId
        val phoneValue = phone()
        val titleValue = title()
        val localRows = LocalCallStatsProvider.buildPopupInfoRows(service, phoneValue)
        render(
            requestId = requestId,
            phoneValue = phoneValue,
            titleValue = titleValue,
            localRows = localRows,
            remoteRows = emptyList(),
        )
        loadRemoteRows(requestId, phoneValue, titleValue, localRows)
    }

    /**
     * Unknown numbers are eligible for the lookup. Known phone contacts are only
     * queried when CRM is on. The network task never delays the first popup.
     */
    private fun loadRemoteRows(
        requestId: Long,
        phoneValue: String,
        titleValue: String,
        localRows: List<String>,
    ) {
        Thread {
            val remoteRows = runCatching {
                PostCallLookupRemoteRows.load(service.applicationContext, phoneValue)
            }.getOrDefault(emptyList())
            if (remoteRows.isEmpty()) return@Thread

            handler.post {
                if (requestId != activeRequestId || phoneValue != phone()) return@post
                render(
                    requestId = requestId,
                    phoneValue = phoneValue,
                    titleValue = titleValue,
                    localRows = localRows,
                    remoteRows = remoteRows,
                )
            }
        }.start()
    }

    private fun render(
        requestId: Long,
        phoneValue: String,
        titleValue: String,
        localRows: List<String>,
        remoteRows: List<PostCallLookupRemoteRow>,
    ) {
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val displayName = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty()
        val titleText = when {
            displayName.isNotBlank() && phoneValue.isNotBlank() -> "$displayName • $phoneValue"
            displayName.isNotBlank() -> displayName
            titleValue.isNotBlank() && titleValue != phoneValue -> "$titleValue • $phoneValue"
            else -> phoneValue.ifBlank { titleValue.ifBlank { "Call Report" } }
        }
        val headerRow = localRows.firstOrNull { row -> !isNoteRow(row) }
        val headerText = headerRow.orEmpty().ifBlank {
            service.getString(R.string.overlay_no_previous_call)
        }
        val localNoteRows = localRows.filter(::isNoteRow)
        val visibleLocalRows = localNoteRows.filterNot { localRow ->
            remoteRows.any { remoteRow ->
                noteTextFromLocalRow(localRow).equals(remoteRow.note, ignoreCase = true)
            }
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28), ui.dp(20), ui.dp(24), ui.dp(18))
            ui.stylePopupCard(this)
        }
        val contentRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val contentColumn = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        contentColumn.addView(TextView(service).apply {
            text = headerText
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(17, 24, 39))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        contentColumn.addView(TextView(service).apply {
            text = titleText
            textSize = 14f
            setTextColor(Color.rgb(75, 85, 99))
            setPadding(0, ui.dp(3), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })

        if (visibleLocalRows.isNotEmpty() || remoteRows.isNotEmpty()) {
            contentColumn.addView(buildDataColumn(visibleLocalRows, remoteRows))
        }
        contentRow.addView(contentColumn)
        contentRow.addView(ui.noteRightAction { showNoteEditor() })
        card.addView(contentRow)
        addDraggableOverlay(ui.shadowScroll(card), false, ui.dp(74), timeoutMs) {
            if (requestId == activeRequestId) {
                activeRequestId += 1L
                showBubbleAfterLookup()
            }
        }
    }

    private fun buildDataColumn(
        localRows: List<String>,
        remoteRows: List<PostCallLookupRemoteRow>,
    ): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            var position = 0
            localRows.forEach { line ->
                addView(localNoteRow(line, position))
                position += 1
            }
            remoteRows.forEach { row ->
                addView(remoteNoteRow(row, position))
                position += 1
            }
        }
    }

    private fun localNoteRow(line: String, position: Int): View {
        val topMargin = if (position == 0) 0 else ui.dp(6)
        return when {
            line.startsWith(ICON_GENERAL_NOTE) -> {
                ui.generalNotePreviewRow(line.removePrefix(ICON_GENERAL_NOTE).trim(), topMargin)
            }
            line.startsWith(ICON_CALL_NOTE) -> {
                ui.notePreviewRow(
                    noteText = line.removePrefix(ICON_CALL_NOTE).trim(),
                    textColor = NoteUiStyle.Call.text,
                    backgroundColor = NoteUiStyle.Call.background,
                    strokeColor = NoteUiStyle.Call.border,
                    topMargin = topMargin,
                    iconRes = R.drawable.ic_chat_note,
                )
            }
            else -> TextView(service).apply {
                text = line
                textSize = 14f
                setTextColor(Color.rgb(75, 85, 99))
                setPadding(0, topMargin, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun remoteNoteRow(row: PostCallLookupRemoteRow, position: Int): View {
        val companyLabel = row.companyName.ifBlank { "Сървър" }
        val text = "$companyLabel · ${row.note}"
        val topMargin = if (position == 0) 0 else ui.dp(6)
        return when (row.kind) {
            PostCallLookupRemoteRow.Kind.GENERAL_NOTE -> ui.generalNotePreviewRow(text, topMargin)
            PostCallLookupRemoteRow.Kind.CALL_NOTE -> ui.notePreviewRow(
                noteText = text,
                textColor = NoteUiStyle.Call.text,
                backgroundColor = NoteUiStyle.Call.background,
                strokeColor = NoteUiStyle.Call.border,
                topMargin = topMargin,
                iconRes = R.drawable.ic_chat_note,
            )
        }
    }

    private fun isNoteRow(value: String): Boolean =
        value.startsWith(ICON_GENERAL_NOTE) || value.startsWith(ICON_CALL_NOTE)

    private fun noteTextFromLocalRow(value: String): String = value
        .removePrefix(ICON_GENERAL_NOTE)
        .removePrefix(ICON_CALL_NOTE)
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val ICON_GENERAL_NOTE = "☰"
        const val ICON_CALL_NOTE = "💬"
    }
}

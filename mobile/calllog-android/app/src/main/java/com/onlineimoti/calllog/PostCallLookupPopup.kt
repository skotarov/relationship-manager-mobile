package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
    private val addDraggableOverlay: (View, Boolean, Int, Long) -> Unit,
    private val showNoteEditor: () -> Unit,
    private val timeoutMs: Long,
) {
    fun show() {
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val phoneValue = phone()
        val titleValue = title()
        val displayName = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty()
        val titleText = when {
            displayName.isNotBlank() && phoneValue.isNotBlank() -> "$displayName • $phoneValue"
            displayName.isNotBlank() -> displayName
            titleValue.isNotBlank() && titleValue != phoneValue -> "$titleValue • $phoneValue"
            else -> phoneValue.ifBlank { titleValue.ifBlank { "Call Report" } }
        }
        val infoRows = LocalCallStatsProvider.buildPopupInfoRows(service, phoneValue)
        val headerText = infoRows.firstOrNull().orEmpty().ifBlank { "Няма предишен разговор" }
        val remainingInfoRows = infoRows.drop(1)

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

        if (remainingInfoRows.isNotEmpty()) {
            contentColumn.addView(buildDataColumn(remainingInfoRows))
        }
        contentRow.addView(contentColumn)
        contentRow.addView(ui.noteRightAction { showNoteEditor() })
        card.addView(contentRow)
        addDraggableOverlay(ui.shadowScroll(card), false, ui.dp(74), timeoutMs)
    }

    private fun buildDataColumn(infoRows: List<String>): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            infoRows.forEachIndexed { index, line ->
                when {
                    line.startsWith("☰") -> {
                        addView(ui.generalNotePreviewRow(line.removePrefix("☰").trim(), if (index == 0) 0 else ui.dp(2)))
                    }
                    line.startsWith("💬") -> {
                        addView(
                            ui.notePreviewRow(
                                noteText = line.removePrefix("💬").trim(),
                                textColor = NoteUiStyle.Call.text,
                                backgroundColor = NoteUiStyle.Call.background,
                                strokeColor = NoteUiStyle.Call.border,
                                topMargin = if (index == 0) 0 else ui.dp(6),
                                iconRes = R.drawable.ic_chat_note,
                            )
                        )
                    }
                    else -> {
                        addView(TextView(service).apply {
                            text = line
                            textSize = 14f
                            setTextColor(Color.rgb(75, 85, 99))
                            setPadding(0, if (index == 0) 0 else ui.dp(2), 0, 0)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        })
                    }
                }
            }
        }
    }
}

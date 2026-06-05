package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
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
    private var started = false
    private var loading = false
    private var error = false
    private var notes: List<CrmServerNote> = emptyList()

    fun loadOnce(phone: String) {
        if (started || phone.isBlank()) return
        val config = ConfigStore.load(activity)
        if (!config.remoteEnabled || config.baseUrl.isBlank()) return
        started = true
        loading = true
        error = false
        rerender()
        executor.execute {
            val result = runCatching { CrmContactHistoryClient.fetch(config, phone) }
            handler.post {
                loading = false
                result.onSuccess {
                    notes = it.serverNotes
                    error = false
                }.onFailure {
                    notes = emptyList()
                    error = true
                }
                rerender()
            }
        }
    }

    fun release() {
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    fun addSection(root: LinearLayout) {
        if (!loading && !error && notes.isEmpty()) return
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }

            addView(headerUi.sectionTitleWithDrawable("CRM история", R.drawable.ic_note_lines))
            when {
                loading -> addView(statusText("Зареждам CRM история…"))
                error -> addView(statusText("CRM историята не е заредена"))
                else -> notes.forEach { note -> addView(noteCard(note)) }
            }
        })
    }

    private fun statusText(value: String): TextView {
        return TextView(activity).apply {
            text = value
            textSize = 13.5f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun noteCard(note: CrmServerNote): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(248, 250, 252), dp(12), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

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
            if (note.propertyTitle.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = "Обява: ${note.propertyTitle}"
                    textSize = 12.5f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun metaText(note: CrmServerNote): String {
        val author = note.authorName.ifBlank { note.authorLogin }.ifBlank { note.authorId }
        return listOf("CRM", author, note.createdAt).filter { it.isNotBlank() }.joinToString(" • ")
    }
}

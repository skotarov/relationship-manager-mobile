package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ContactNotesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележки" } }
        setContentView(buildContent(phone, title))
    }

    private fun buildContent(phone: String, title: String): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        root.addView(TextView(this).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        })
        if (phone.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = phone
                textSize = 14f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(2), 0, dp(14))
            })
        }

        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)
        root.addView(sectionTitle("Основна бележка"))
        root.addView(
            plainNoteCard(
                generalNote.ifBlank { "Няма основна бележка към този контакт/номер." },
                muted = generalNote.isBlank(),
            )
        )

        val callNotes = ContactNoteReader.callNotesForPhone(phone)
        root.addView(sectionTitle("Бележки от разговори"))
        if (callNotes.isEmpty()) {
            root.addView(plainNoteCard("Няма бележки към разговори.", muted = true))
        } else {
            callNotes.forEach { note -> root.addView(callNoteCard(note)) }
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun sectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(0, dp(14), 0, dp(8))
        }
    }

    private fun plainNoteCard(textValue: String, muted: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14.5f
            setTextColor(if (muted) Color.rgb(100, 116, 139) else Color.rgb(30, 41, 59))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(226, 232, 240), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun callNoteCard(note: ContactCallNote): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(224, 246, 255), dp(12), Color.rgb(125, 211, 252), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@ContactNotesActivity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    PhoneCallReader.formatDuration(note.durationSeconds),
                    PhoneCallReader.directionLabel(note.direction),
                ).filter { it.isNotBlank() }.joinToString(" • ")
                textSize = 12.5f
                setTextColor(Color.rgb(7, 89, 133))
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = note.note
                textSize = 14.5f
                setTextColor(Color.rgb(8, 47, 73))
                setPadding(0, dp(5), 0, 0)
            })
        }
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_TITLE = "title"
    }
}

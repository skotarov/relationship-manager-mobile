package com.onlineimoti.calllog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележки" } }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        setContentView(buildContent(phone, titleText))
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

        root.addView(headerRow(title))
        if (phone.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = phone
                textSize = 14f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, dp(2), 0, dp(10))
            })
        }

        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)
        root.addView(sectionTitleWithDrawable("Основна бележка", R.drawable.ic_note_lines))
        root.addView(generalNoteCard(generalNote.ifBlank { "Няма основна бележка към този контакт/номер." }, muted = generalNote.isBlank()))

        val callNotes = ContactNoteReader.callNotesForPhone(phone)
        root.addView(sectionTitleWithEmoji("Бележки от разговори", "💬"))
        if (callNotes.isEmpty()) {
            root.addView(plainNoteCard("Няма бележки към разговори.", muted = true))
        } else {
            callNotes.forEach { note -> root.addView(callNoteCard(note)) }
        }

        return ScrollView(this).apply { addView(root) }
    }

    private fun headerRow(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ContactNotesActivity).apply {
                text = "‹"
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(15, 23, 42))
                background = null
                isClickable = true
                isFocusable = true
                setOnClickListener { openAllCallsLog() }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = "Информация"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(15, 23, 42))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(R.drawable.ic_calendar_event, "Календар") { openCalendarEvent() })
        }
    }

    private fun allCallsButton(): TextView {
        return TextView(this).apply {
            text = "Всички обаждания"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            setTextColor(Color.rgb(14, 116, 144))
            setPadding(0, dp(4), 0, dp(8))
            background = null
            isClickable = true
            isFocusable = true
            setOnClickListener { openAllCallsLog() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
    }

    private fun openAllCallsLog() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun sectionTitleWithDrawable(textValue: String, drawableRes: Int): LinearLayout {
        return titleRow(textValue).apply {
            addView(ImageView(this@ContactNotesActivity).apply {
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            }, 0)
        }
    }

    private fun sectionTitleWithEmoji(textValue: String, emoji: String): LinearLayout {
        return titleRow(textValue).apply {
            addView(TextView(this@ContactNotesActivity).apply {
                text = emoji
                textSize = 17f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            }, 0)
        }
    }

    private fun titleRow(textValue: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
            addView(TextView(this@ContactNotesActivity).apply {
                text = textValue
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(30, 41, 59))
            })
        }
    }

    private fun generalNoteCard(textValue: String, muted: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14.5f
            setTextColor(if (muted) Color.rgb(100, 116, 139) else Color.rgb(30, 41, 59))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(226, 232, 240), dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { openGeneralNotePopup() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun plainNoteCard(textValue: String, muted: Boolean): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 14.5f
            setTextColor(if (muted) Color.rgb(100, 116, 139) else Color.rgb(30, 41, 59))
            setPadding(0, dp(2), 0, dp(8))
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
    }

    private fun callNoteCard(note: ContactCallNote): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(224, 246, 255), dp(12), Color.rgb(125, 211, 252), dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { openEditPopup(note) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(TextView(this@ContactNotesActivity).apply {
                text = listOf(
                    PhoneCallReader.formatStartedAt(note.callAt.takeIf { it > 0L } ?: note.savedAt),
                    directionArrowLabel(note.direction),
                    PhoneCallReader.formatDuration(note.durationSeconds),
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

    private fun directionArrowLabel(direction: String): String {
        return when (direction) {
            "in" -> "↙ входящ"
            "out" -> "↗ изходящ"
            else -> PhoneCallReader.directionLabel(direction)
        }
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = null
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) }
            setOnClickListener { action() }
        }
    }

    private fun openCalendarEvent() {
        val safeName = titleText.ifBlank { phone.ifBlank { "контакт" } }
        val eventTitle = "Среща с $safeName"
        val description = buildString {
            appendLine("Име: $safeName")
            if (phone.isNotBlank()) appendLine("Телефон: $phone")
        }.trim()
        val begin = System.currentTimeMillis() + 60 * 60 * 1000L
        val end = begin + 60 * 60 * 1000L
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, eventTitle)
            putExtra(CalendarContract.Events.DESCRIPTION, description)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Няма намерено приложение Календар", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGeneralNotePopup() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши 'Показване върху други приложения', за да редактираш основната бележка.", Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_GENERAL_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, titleText)
        )
    }

    private fun openEditPopup(note: ContactCallNote) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши 'Показване върху други приложения', за да редактираш бележката.", Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_NOTE)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, note.direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, titleText)
                .putExtra(PostCallOverlayService.EXTRA_CALL_AT, note.callAt)
                .putExtra(PostCallOverlayService.EXTRA_DURATION, note.durationSeconds)
        )
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

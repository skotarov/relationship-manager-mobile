package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var contactRegistrationBusy = false

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

        root.addView(headerRow())
        val contactInfoText = listOfNotNull(
            title.takeIf { it.isNotBlank() && it != phone && it != "Бележки" },
            phone.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
        if (contactInfoText.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = contactInfoText
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(0, dp(4), 0, dp(12))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        root.addView(contactRegistrationToggle())

        val cards = contactNotesCards()
        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)
        root.addView(sectionTitleWithDrawable("Основна бележка", R.drawable.ic_note_lines))
        root.addView(cards.generalNoteCard(generalNote.ifBlank { "+ Добави" }, muted = generalNote.isBlank()) { openGeneralNotePopup() })

        val callNotes = ContactNoteReader.callNotesForPhone(phone)
        root.addView(sectionTitleWithDrawable("Бележки от разговори", R.drawable.ic_chat_note))
        callNotes.forEach { note -> root.addView(cards.callNoteCard(note) { openEditPopup(note) }) }

        return ScrollView(this).apply { addView(root) }
    }

    private fun contactNotesCards(): ContactNotesCards {
        return ContactNotesCards(
            activity = this,
            dp = ::dp,
            roundedRect = ::roundedRect,
            directionArrowLabel = ::directionArrowLabel,
        )
    }

    private fun headerRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconButton(R.drawable.ic_arrow_back, "Към лога") { openAllCallsLog() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(38)).apply { marginEnd = dp(8) }
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = "Информация"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(15, 23, 42))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(R.drawable.ic_phone_call, "Обади се") { openDialer() })
            addView(iconButton(R.drawable.ic_calendar_event, "Календар") { openCalendarEvent() })
        }
    }

    private fun contactRegistrationToggle(): TextView {
        val linked = CallReportContactIntegration.isContactLinked(this, phone)
        return TextView(this).apply {
            text = when {
                contactRegistrationBusy -> "Обработва се…"
                linked -> "Премахни от Call Report контактите"
                else -> "Регистрирай в Call Report контактите"
            }
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (linked) Color.rgb(185, 28, 28) else Color.rgb(14, 116, 144))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(Color.WHITE, dp(12), if (linked) Color.rgb(252, 165, 165) else Color.rgb(125, 211, 252), dp(1))
            isEnabled = !contactRegistrationBusy
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleContactRegistration(linked) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun toggleContactRegistration(currentlyLinked: Boolean) {
        if (phone.isBlank() || contactRegistrationBusy) return
        contactRegistrationBusy = true
        render()

        val appContext = applicationContext
        val phoneValue = phone
        val titleValue = titleText
        Thread {
            val message = if (currentlyLinked) {
                val deleted = CallReportContactIntegration.removeContact(appContext, phoneValue)
                if (deleted > 0) "Премахнато от Call Report контактите" else "Няма намерен Call Report запис"
            } else {
                val saved = CallReportContactIntegration.linkContact(appContext, phoneValue, titleValue)
                if (saved) "Регистрирано в Call Report контактите" else "Не успях да регистрирам контакта"
            }

            runOnUiThread {
                contactRegistrationBusy = false
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        }.start()
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

    private fun openDialer() {
        if (phone.isBlank()) return
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
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

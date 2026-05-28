package com.onlineimoti.calllog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

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

        root.addView(headerRow(title, phone))
        root.addView(contactActionRow())

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

    private fun headerRow(title: String, phone: String): LinearLayout {
        val mainTitle = title.takeIf { it.isNotBlank() && it != "Бележки" }
            ?: phone.takeIf { it.isNotBlank() }
            ?: "Информация"
        val phoneLine = phone.takeIf { it.isNotBlank() && it != mainTitle }.orEmpty()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(iconButton(R.drawable.ic_arrow_back, "Към лога") { openAllCallsLog() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) }
            })
            addView(LinearLayout(this@ContactNotesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@ContactNotesActivity).apply {
                    text = mainTitle
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(15, 23, 42))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (phoneLine.isNotBlank()) {
                    addView(TextView(this@ContactNotesActivity).apply {
                        text = phoneLine
                        textSize = 15.5f
                        setTextColor(Color.rgb(71, 85, 105))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            })
            addView(iconButton(R.drawable.ic_phone_call, "Обади се") { openDialer() })
            addView(iconButton(R.drawable.ic_calendar_event, "Календар") { openCalendarEvent() })
        }
    }

    private fun contactActionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(contactRegistrationToggle())
            addView(openDefaultContactButton())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun contactRegistrationToggle(): LinearLayout {
        val linked = CallReportContactIntegration.isContactLinked(this, phone)
        val actionColor = when {
            contactRegistrationBusy -> Color.rgb(100, 116, 139)
            linked -> Color.rgb(220, 38, 38)
            else -> Color.rgb(22, 163, 74)
        }
        val iconRes = if (linked) R.drawable.ic_crm_person_remove else R.drawable.ic_crm_person_add
        val labelRes = when {
            contactRegistrationBusy -> R.string.crm_contact_processing
            linked -> R.string.crm_remove_contact
            else -> R.string.crm_add_contact
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = roundedRect(Color.WHITE, dp(16), actionColor, dp(1))
            isEnabled = !contactRegistrationBusy
            isClickable = true
            isFocusable = true
            contentDescription = getString(labelRes)
            setOnClickListener { toggleContactRegistration(linked) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

            addView(ImageView(this@ContactNotesActivity).apply {
                setImageResource(iconRes)
                setColorFilter(actionColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) }
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = getString(labelRes)
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(actionColor)
                includeFontPadding = false
            })
        }
    }

    private fun openDefaultContactButton(): LinearLayout {
        val label = getString(R.string.open_default_contact)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedRect(Color.WHITE, dp(14), Color.rgb(226, 232, 240), dp(1))
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { openDefaultContact() }
            layoutParams = LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(8)
            }

            addView(ImageView(this@ContactNotesActivity).apply {
                setImageResource(R.drawable.ic_contact_open)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
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

    private fun openDefaultContact() {
        if (phone.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val lookupUri = runCatching {
            contentResolver.query(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon().appendPath(phone).build(),
                arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val lookupKey = cursor.getString(0)
                val contactId = cursor.getLong(1)
                ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
            }
        }.getOrNull()

        if (lookupUri == null) {
            Toast.makeText(this, getString(R.string.contact_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        runCatching { startActivity(Intent(Intent.ACTION_VIEW, lookupUri)) }
            .onFailure { Toast.makeText(this, getString(R.string.contacts_app_not_found), Toast.LENGTH_SHORT).show() }
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

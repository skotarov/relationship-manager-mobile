package com.onlineimoti.calllog

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var contactUpdateBusy = false
    private var contactAutoCheckStarted = false
    private var notesChangedReceiverRegistered = false

    private val notesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PostCallOverlayService.ACTION_NOTES_CHANGED) render()
        }
    }

    private val externalActions by lazy { ContactNotesExternalActions(this) }
    private val headerUi by lazy { ContactNotesHeaderUi(this, ::dp) }
    private val sectionsUi by lazy {
        ContactNotesSectionsUi(
            activity = this,
            headerUi = headerUi,
            cards = contactNotesCards(),
            dp = ::dp,
            roundedRect = ::roundedRect,
        )
    }
    private val crmController by lazy {
        ContactNotesCrmController(
            activity = this,
            getPhone = { phone },
            getTitle = { titleText },
            setBusy = { contactUpdateBusy = it },
            rerender = ::render,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележки" } }
        render()
        autoUpdateContactLinkOnce()
    }

    override fun onStart() {
        super.onStart()
        registerNotesChangedReceiver()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onStop() {
        unregisterNotesChangedReceiver()
        super.onStop()
    }

    private fun registerNotesChangedReceiver() {
        if (notesChangedReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            notesChangedReceiver,
            IntentFilter(PostCallOverlayService.ACTION_NOTES_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        notesChangedReceiverRegistered = true
    }

    private fun unregisterNotesChangedReceiver() {
        if (!notesChangedReceiverRegistered) return
        runCatching { unregisterReceiver(notesChangedReceiver) }
        notesChangedReceiverRegistered = false
    }

    private fun render() {
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
        }

        root.addView(headerRow())
        if (shouldShowContactUpdateStatus()) root.addView(contactUpdateStatusRow())
        sectionsUi.addGeneralNote(root, phone) { externalActions.openGeneralNotePopup(phone, titleText) }
        sectionsUi.addCallNotes(
            root = root,
            phone = phone,
            onAddLatestCallNote = ::openCallNoteEditor,
            onEditCallNote = ::openCallNoteEditor,
        )

        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
            addView(root)
        }
    }

    private fun shouldShowContactUpdateStatus(): Boolean = contactUpdateBusy

    private fun contactUpdateStatusRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect(Color.WHITE, dp(14), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }

            addView(ProgressBar(this@ContactNotesActivity, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) }
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = "Updating…"
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(71, 85, 105))
                includeFontPadding = false
            })
        }
    }

    private fun headerRow(): LinearLayout {
        return headerUi.headerRow(
            title = titleText,
            phone = phone,
            contactExists = externalActions.hasDefaultContact(phone),
            goBack = ::finish,
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
        )
    }

    private fun autoUpdateContactLinkOnce() {
        if (contactAutoCheckStarted || phone.isBlank()) return
        contactAutoCheckStarted = true
        crmController.reconcileCurrentPhone()
    }

    private fun openCallNoteEditor(note: ContactCallNote) {
        externalActions.openEditPopup(phone, titleText, note)
    }

    private fun contactNotesCards(): ContactNotesCards {
        return ContactNotesCards(
            activity = this,
            dp = ::dp,
            roundedRect = ::roundedRect,
            directionArrowLabel = headerUi::directionArrowLabel,
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

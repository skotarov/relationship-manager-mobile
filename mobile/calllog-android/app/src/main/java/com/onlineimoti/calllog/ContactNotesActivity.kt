package com.onlineimoti.calllog

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var contactRegistrationBusy = false
    private var notesChangedReceiverRegistered = false

    private val notesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PostCallOverlayService.ACTION_NOTES_CHANGED) render()
        }
    }

    private val externalActions by lazy { ContactNotesExternalActions(this) }
    private val headerUi by lazy { ContactNotesHeaderUi(this, ::dp) }
    private val actionRowUi by lazy { ContactNotesActionRowUi(this, ::dp, ::roundedRect) }
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
            setBusy = { contactRegistrationBusy = it },
            rerender = ::render,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { phone.ifBlank { "Бележки" } }
        render()
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
        if (ConfigStore.load(this).showCrmActionButtons) root.addView(contactActionRow())
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

    private fun headerRow(): LinearLayout {
        return headerUi.headerRow(
            title = titleText,
            phone = phone,
            contactExists = externalActions.hasDefaultContact(phone),
            openAllCallsLog = externalActions::openAllCallsLog,
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
        )
    }

    private fun contactActionRow(): LinearLayout {
        val linked = crmController.isLinked()
        return actionRowUi.contactActionRow(
            linked = linked,
            busy = contactRegistrationBusy,
            onToggle = { crmController.toggle(linked) },
            onEditCrm = crmController::showDialog,
        )
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
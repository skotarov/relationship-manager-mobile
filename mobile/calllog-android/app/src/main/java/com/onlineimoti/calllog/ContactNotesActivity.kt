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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private val crmHistoryController by lazy {
        ContactNotesCrmHistoryController(
            activity = this,
            headerUi = headerUi,
            dp = ::dp,
            roundedRect = ::roundedRect,
            rerender = ::render,
        )
    }
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
        crmHistoryController.loadOnce(phone)
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

    override fun onDestroy() {
        crmHistoryController.release()
        super.onDestroy()
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
        val config = ConfigStore.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
        }

        root.addView(headerRow())
        if (config.remoteEnabled) root.addView(crmSyncToggleRow())
        if (config.showRmDebugBox) root.addView(rmDebugBlock())
        sectionsUi.addGeneralNote(root, phone) { externalActions.openGeneralNotePopup(phone, titleText) }
        crmHistoryController.addSection(root, phone, ::openCallNoteEditor)

        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
            addView(root)
        }
    }

    private fun crmSyncToggleRow(): TextView {
        val enabled = CrmContactSyncStore.isEnabled(this, phone)
        return TextView(this).apply {
            text = if (enabled) "Към CRM: включено" else "Само локално"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(if (enabled) Color.rgb(20, 83, 45) else Color.rgb(71, 85, 105))
            background = if (enabled) {
                roundedRect(Color.rgb(220, 252, 231), dp(16), Color.rgb(134, 239, 172), dp(1))
            } else {
                roundedRect(Color.rgb(241, 245, 249), dp(16), Color.rgb(203, 213, 225), dp(1))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                val nowEnabled = CrmContactSyncStore.toggle(this@ContactNotesActivity, phone)
                Toast.makeText(
                    this@ContactNotesActivity,
                    if (nowEnabled) "Новите бележки ще се изпращат към CRM" else "Новите бележки ще остават само локално",
                    Toast.LENGTH_SHORT,
                ).show()
                render()
            }
        }
    }

    private fun rmDebugBlock(): TextView {
        return TextView(this).apply {
            text = buildDebugText()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(71, 85, 105))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedRect(Color.rgb(248, 250, 252), dp(10), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
    }

    private fun buildDebugText(): String {
        val debugText = RmContactDebugReader.debugText(this, phone, titleText)
        return if (contactUpdateBusy) {
            "RM progress: Updating…\n$debugText"
        } else {
            debugText
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

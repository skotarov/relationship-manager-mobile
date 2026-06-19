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
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var backTargetsUnfilteredHome = false
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
        backTargetsUnfilteredHome = intent.getBooleanExtra(EXTRA_BACK_TARGETS_UNFILTERED_HOME, false)
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
        PendingCallNoteStore.reconcilePendingForPhone(this, phone)
        crmHistoryController.addSection(
            root = root,
            phone = phone,
            openFilteredLog = { openRmCallLog(filtered = true) },
            onEditCallNote = ::openCallNoteEditor,
        )

        return ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@ContactNotesActivity, R.color.calllog_bg))
            addView(root)
        }
    }

    private fun crmSyncToggleRow(): LinearLayout {
        val enabled = CrmContactSyncStore.isEnabled(this, phone)
        lateinit var checkBox: CheckBox
        val toggle = {
            val nowEnabled = CrmContactSyncStore.toggle(this@ContactNotesActivity, phone)
            checkBox.isChecked = nowEnabled
            Toast.makeText(
                this@ContactNotesActivity,
                if (nowEnabled) "Синхронизацията е включена" else "Синхронизацията е изключена",
                Toast.LENGTH_SHORT,
            ).show()
            render()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(2), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            checkBox = CheckBox(this@ContactNotesActivity).apply {
                isChecked = enabled
                contentDescription = "Синхронизирай"
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(2) }
                setOnClickListener { toggle() }
            }
            addView(checkBox)
            addView(ImageView(this@ContactNotesActivity).apply {
                setImageResource(R.drawable.ic_cloud_sync)
                scaleType = ImageView.ScaleType.FIT_CENTER
                alpha = if (enabled) 1f else 0.55f
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) }
            })
            addView(TextView(this@ContactNotesActivity).apply {
                text = "Синхронизирай"
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (enabled) Color.rgb(15, 23, 42) else Color.rgb(100, 116, 139))
            })
            setOnClickListener { toggle() }
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
            showRmCallLogButton = !backTargetsUnfilteredHome,
            goBack = ::finish,
            openDialer = { externalActions.openDialer(phone) },
            openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            openDefaultContact = { externalActions.openDefaultContact(phone, titleText) },
            openRmCallLog = { openRmCallLog(filtered = false) },
            openRmCallLogFiltered = { openRmCallLog(filtered = true) },
        )
    }

    private fun openRmCallLog(filtered: Boolean) {
        startActivity(
            Intent(this, HomeActivity::class.java).apply {
                if (filtered && phone.isNotBlank()) putExtra(HomeActivity.EXTRA_PHONE_FILTER, phone)
            }
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
        const val EXTRA_BACK_TARGETS_UNFILTERED_HOME = "back_targets_unfiltered_home"
    }
}

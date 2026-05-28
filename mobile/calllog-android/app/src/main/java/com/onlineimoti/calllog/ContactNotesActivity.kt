package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ContactNotesActivity : Activity() {
    private var phone: String = ""
    private var titleText: String = ""
    private var contactRegistrationBusy = false

    private val externalActions by lazy { ContactNotesExternalActions(this) }
    private val headerUi by lazy { ContactNotesHeaderUi(this, ::dp) }
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

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }

        root.addView(
            headerUi.headerRow(
                title = titleText,
                phone = phone,
                openAllCallsLog = externalActions::openAllCallsLog,
                openDialer = { externalActions.openDialer(phone) },
                openCalendarEvent = { externalActions.openCalendarEvent(phone, titleText) },
            )
        )
        root.addView(contactActionRow())
        addGeneralNote(root)
        addCallNotes(root)

        return ScrollView(this).apply { addView(root) }
    }

    private fun addGeneralNote(root: LinearLayout) {
        val generalNote = ContactNoteReader.generalNoteForPhone(this, phone)
        val cards = contactNotesCards()
        root.addView(headerUi.sectionTitleWithDrawable("Основна бележка", R.drawable.ic_note_lines))
        root.addView(
            cards.generalNoteCard(
                generalNote.ifBlank { "+ Добави" },
                muted = generalNote.isBlank(),
            ) { externalActions.openGeneralNotePopup(phone, titleText) }
        )
    }

    private fun addCallNotes(root: LinearLayout) {
        val cards = contactNotesCards()
        root.addView(headerUi.sectionTitleWithDrawable("Бележки от разговори", R.drawable.ic_chat_note))
        ContactNoteReader.callNotesForPhone(phone).forEach { note ->
            root.addView(cards.callNoteCard(note) { externalActions.openEditPopup(phone, titleText, note) })
        }
    }

    private fun contactNotesCards(): ContactNotesCards {
        return ContactNotesCards(
            activity = this,
            dp = ::dp,
            roundedRect = ::roundedRect,
            directionArrowLabel = headerUi::directionArrowLabel,
        )
    }

    private fun contactActionRow(): LinearLayout {
        val linked = crmController.isLinked()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(contactRegistrationToggle(linked))
            if (linked) addView(editCrmContactButton())
            addView(openDefaultContactButton())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun contactRegistrationToggle(linked: Boolean): LinearLayout {
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
            setOnClickListener { if (!contactRegistrationBusy) crmController.toggle(linked) }

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

    private fun editCrmContactButton(): TextView {
        return TextView(this).apply {
            text = "Едит"
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(37, 99, 235))
            background = roundedRect(Color.WHITE, dp(14), Color.rgb(37, 99, 235), dp(1))
            isClickable = true
            isFocusable = true
            contentDescription = "Редактирай CRM полета"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { crmController.showDialog() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { marginStart = dp(8) }
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
            setOnClickListener { externalActions.openDefaultContact(phone) }
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

package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Main-note section with one independent phase row directly below each company note. */
internal class CompanyScopedGeneralNoteSectionUi(
    private val activity: Activity,
    private val headerUi: ContactNotesHeaderUi,
    private val cards: ContactNotesCards,
    private val dp: (Int) -> Int,
    private val roundedRect: (color: Int, radius: Int, strokeColor: Int, strokeWidth: Int) -> GradientDrawable,
) {
    fun add(
        root: LinearLayout,
        phone: String,
        companyNotes: List<CallReportCompanyMainNote>,
        showCompanyNotes: Boolean,
        onEditCompany: (String) -> Unit,
        phaseBarForCompany: ((String) -> View)?,
    ) {
        val section = sectionContainer()
        root.addView(section)
        section.addView(headerUi.sectionTitleWithDrawable(activity.getString(R.string.dynamic_note_general_title), R.drawable.ic_note_lines))
        addLocalNote(section, phone, onEditCompany)
        if (!showCompanyNotes || !ContactServerCompanyScope.isAvailable(activity, phone)) return

        companyNotes.forEach { companyNote ->
            section.addView(companyLabel(companyNote.companyName, showCloud = true))
            val card = cards.generalNoteCard(
                textValue = companyNote.note.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                muted = companyNote.note.isBlank(),
                serverConfirmed = companyNote.confirmedByServer,
                syncStatusText = if (companyNote.pending) activity.getString(R.string.history_pending_server_sync) else "",
                onClick = { onEditCompany(companyNote.companyId) },
            )
            if (phaseBarForCompany != null) {
                (card.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(2)
            }
            section.addView(card)
            phaseBarForCompany?.invoke(companyNote.companyId)?.let(section::addView)
        }
    }

    private fun addLocalNote(section: LinearLayout, phone: String, onEditCompany: (String) -> Unit) {
        val note = ContactNoteReader.generalNoteForPhone(activity, phone)
        val pending = CallReportDeferredCompanyAssignmentStore.isGeneralPending(activity, phone)
        section.addView(companyLabel(activity.getString(R.string.note_local_company)))
        section.addView(
            cards.generalNoteCard(
                textValue = note.ifBlank { activity.getString(R.string.dynamic_notes_add_general) },
                muted = note.isBlank(),
                serverConfirmed = false,
                syncStatusText = if (pending) activity.getString(R.string.dynamic_note_pending_company_choice) else "",
                onClick = { onEditCompany(ContactNoteTopicState.LOCAL_COMPANY_ID) },
            )
        )
    }

    private fun companyLabel(name: String, showCloud: Boolean = false): TextView = TextView(activity).apply {
        text = name
        textSize = 12.5f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(dp(2), dp(8), dp(2), dp(3))
        if (showCloud) {
            activity.getDrawable(R.drawable.ic_cloud_note)?.apply {
                setBounds(0, 0, dp(14), dp(14))
                setCompoundDrawables(this, null, null, null)
                compoundDrawablePadding = dp(4)
            }
        }
    }

    private fun sectionContainer(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = roundedRect(Color.WHITE, dp(18), Color.rgb(218, 220, 224), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(14) }
    }
}

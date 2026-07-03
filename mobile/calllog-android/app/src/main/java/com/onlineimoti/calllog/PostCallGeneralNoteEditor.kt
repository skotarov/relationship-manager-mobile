package com.onlineimoti.calllog

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal class PostCallGeneralNoteEditor(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val handler: Handler,
    private val phone: () -> String,
    private val preferredCompanyId: () -> String,
    private val setPreferredCompanyId: (String) -> Unit,
    private val setWindowManager: (WindowManager) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long) -> Unit,
    private val showNoteEditor: () -> Unit,
    private val openCalendarEvent: (String) -> Unit,
    private val openContactNotesScreen: () -> Unit,
    private val pendingGeneralNote: () -> String?,
    private val setPendingGeneralNote: (String) -> Unit,
    @Suppress("unused") private val savePendingNoteChangesBeforeHistory: () -> Boolean,
    private val notifyNotesChanged: () -> Unit,
    private val stopOverlay: () -> Unit,
) {
    fun show() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val phoneValue = phone()
        val displayName = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty()
        val titleText = displayName.ifBlank { phoneValue.ifBlank { "Основна бележка" } }
        val initialCompanyId = preferredCompanyId().trim()
        if (initialCompanyId.isNotBlank()) setPreferredCompanyId(initialCompanyId)
        val draft = ContactNoteFormDraft(phone = phoneValue, title = titleText, isGeneralNote = true)
        val form = OverlayContactNoteFormController(
            service = service,
            handler = handler,
            dp = ui::dp,
            draft = draft,
            preferredCompanyId = initialCompanyId,
        )
        val generalNote = pendingGeneralNote() ?: ContactNoteReader.generalNoteForPhone(service, phoneValue)

        fun saveCurrent(noteText: String, transition: Boolean): Boolean {
            setPendingGeneralNote(noteText)
            if (!form.hasChangedText(noteText)) {
                setPreferredCompanyId(form.effectiveCompanyId().ifBlank { initialCompanyId })
                return true
            }
            val result = if (transition) form.saveForTransition(noteText) else form.save(noteText) ?: return false
            if (!result.saved) return false
            form.markTextPersisted(noteText)
            val destination = form.effectiveCompanyId().ifBlank {
                if (result.localOnlyFallback) ContactNoteTopicState.LOCAL_COMPANY_ID else initialCompanyId
            }
            setPreferredCompanyId(destination)
            notifyNotesChanged()
            return true
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
            ui.stylePopupCard(this)
        }

        val titleRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(ImageView(service).apply {
            setImageResource(R.drawable.ic_note_lines)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
            layoutParams = LinearLayout.LayoutParams(ui.dp(35), ui.dp(35)).apply { marginEnd = ui.dp(8) }
        })
        titleRow.addView(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(service).apply {
                text = "Основна бележка"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(17, 24, 39))
            })
            addView(TextView(service).apply {
                text = titleText
                textSize = 13f
                setTextColor(Color.rgb(107, 114, 128))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        })
        val generalNoteInput = ui.noteEditText(generalNote, "Основна бележка към контакта/номера", 4, ui.dp(12))
        titleRow.addView(ui.iconAction(R.drawable.ic_calendar_event) {
            if (saveCurrent(generalNoteInput.text?.toString().orEmpty(), transition = true)) {
                openCalendarEvent(titleText)
            } else {
                Toast.makeText(service, "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
            }
        })
        titleRow.addView(ui.iconAction(R.drawable.ic_popup_close) {
            if (saveCurrent(generalNoteInput.text?.toString().orEmpty(), transition = true)) {
                stopOverlay()
            } else {
                Toast.makeText(service, "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(titleRow)
        form.addTopicFieldTo(card, generalNoteInput)
        card.addView(generalNoteInput)
        card.addView(actionRow(
            input = generalNoteInput,
            saveCurrent = ::saveCurrent,
            showCallNote = showNoteEditor,
            openHistory = openContactNotesScreen,
            close = stopOverlay,
        ))

        addDraggableOverlay(ui.shadowScroll(card), true, ui.dp(135), 0L)
        generalNoteInput.requestFocus()
        handler.postDelayed({
            (service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(generalNoteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun actionRow(
        input: EditText,
        saveCurrent: (String, Boolean) -> Boolean,
        showCallNote: () -> Unit,
        openHistory: () -> Unit,
        close: () -> Unit,
    ): LinearLayout {
        return LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, ui.dp(12), 0, 0)
            addView(ui.secondaryIconAction(R.drawable.ic_chat_note, "Бележка") {
                if (saveCurrent(input.text?.toString().orEmpty(), true)) showCallNote()
                else Toast.makeText(service, "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
            })
            addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
            addView(ui.secondaryTextAction("История") {
                if (saveCurrent(input.text?.toString().orEmpty(), true)) openHistory()
                else Toast.makeText(service, "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
            })
            addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
            addView(ui.textAction("Запази") {
                if (saveCurrent(input.text?.toString().orEmpty(), false)) {
                    Toast.makeText(service, "Основната бележка е записана", Toast.LENGTH_SHORT).show()
                    close()
                } else {
                    Toast.makeText(service, "Не успях да запиша основната бележка", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}

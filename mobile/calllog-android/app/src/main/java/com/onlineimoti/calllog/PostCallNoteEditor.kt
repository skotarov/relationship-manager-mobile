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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal class PostCallNoteEditor(
    private val service: Service,
    private val ui: PostCallOverlayUi,
    private val handler: Handler,
    private val phone: () -> String,
    private val direction: () -> String,
    private val callAt: () -> Long,
    private val durationSeconds: () -> Long,
    private val actionIssuedAt: () -> Long,
    private val callDirectionColor: (String) -> Int,
    private val setWindowManager: (WindowManager) -> Unit,
    private val removeOverlay: () -> Unit,
    private val addDraggableOverlay: (View, Boolean, Int, Long) -> Unit,
    private val showGeneralNoteEditor: () -> Unit,
    private val openCalendarEvent: (String) -> Unit,
    private val openContactNotesScreen: () -> Unit,
    private val pendingCallNote: () -> String?,
    private val setPendingCallNote: (String) -> Unit,
    private val savePendingNoteChangesBeforeHistory: () -> Boolean,
    private val notifyNotesChanged: () -> Unit,
    private val stopOverlay: () -> Unit,
) {
    fun show() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        setWindowManager(service.getSystemService(Context.WINDOW_SERVICE) as WindowManager)

        val phoneValue = phone()
        val directionValue = direction()
        val callAtValue = callAt()
        val durationValue = durationSeconds()
        val displayName = ContactGroupFilter.resolveDisplayName(service, phoneValue).orEmpty()
        val titleText = displayName.ifBlank { phoneValue.ifBlank { "Бележка към обаждане" } }
        val callNote = pendingCallNote() ?: ContactNoteReader.callNoteForPhone(service, phoneValue, callAtValue, directionValue)

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
            setImageResource(R.drawable.ic_chat_note)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            layoutParams = LinearLayout.LayoutParams(ui.dp(35), ui.dp(35)).apply { marginEnd = ui.dp(8) }
        })
        titleRow.addView(LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(service).apply {
                text = "Бележка от разговора"
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
        val callNoteInput = ui.callNoteEditText(callNote, "Бележка към това обаждане", 3, ui.dp(8))
        titleRow.addView(ui.iconAction(R.drawable.ic_calendar_event) {
            val noteText = callNoteInput.text?.toString().orEmpty()
            setPendingCallNote(noteText)
            if (saveCallNote(phoneValue, directionValue, callAtValue, durationValue, noteText)) {
                notifyNotesChanged()
                openCalendarEvent(titleText)
            } else {
                Toast.makeText(service, "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            }
        })
        titleRow.addView(ui.iconAction(R.drawable.ic_popup_close) { stopOverlay() })
        card.addView(titleRow)

        if (callAtValue > 0L) {
            val infoRow = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(12), 0, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            fun addInfoPart(value: String, textColor: Int, bold: Boolean = false) {
                if (value.isBlank()) return
                if (infoRow.childCount > 0) {
                    infoRow.addView(TextView(service).apply {
                        text = " • "
                        textSize = 13f
                        setTextColor(Color.rgb(107, 114, 128))
                    })
                }
                infoRow.addView(TextView(service).apply {
                    text = value
                    textSize = 13f
                    if (bold) typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addInfoPart(PhoneCallReader.directionLabel(directionValue), callDirectionColor(directionValue))
            addInfoPart(PhoneCallReader.formatStartedAt(callAtValue), Color.rgb(107, 114, 128), bold = true)
            addInfoPart(PhoneCallReader.formatDuration(durationValue), Color.rgb(107, 114, 128))
            card.addView(infoRow)
        }

        card.addView(callNoteInput)

        val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, ui.dp(12), 0, 0)
        }
        actions.addView(ui.secondaryIconAction(R.drawable.ic_note_lines, "Основна") {
            setPendingCallNote(callNoteInput.text?.toString().orEmpty())
            if (savePendingNoteChangesBeforeHistory()) {
                showGeneralNoteEditor()
            } else {
                Toast.makeText(service, "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            }
        })
        actions.addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        actions.addView(ui.secondaryTextAction("История") {
            setPendingCallNote(callNoteInput.text?.toString().orEmpty())
            if (savePendingNoteChangesBeforeHistory()) {
                openContactNotesScreen()
            } else {
                Toast.makeText(service, "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            }
        })
        actions.addView(View(service).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 1) })
        actions.addView(ui.textAction("Запази") {
            val callSaved = saveCallNote(
                phoneNumber = phoneValue,
                directionValue = directionValue,
                callAtValue = callAtValue,
                durationValue = durationValue,
                noteText = callNoteInput.text?.toString().orEmpty(),
            )
            if (callSaved) notifyNotesChanged()
            Toast.makeText(service, if (callSaved) "Бележката към обаждането е записана" else "Не успях да запиша бележката", Toast.LENGTH_SHORT).show()
            stopOverlay()
        })
        card.addView(actions)

        addDraggableOverlay(ui.shadowScroll(card), true, ui.dp(135), 0L)
        callNoteInput.requestFocus()
        handler.postDelayed({
            (service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(callNoteInput, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    private fun saveCallNote(
        phoneNumber: String,
        directionValue: String,
        callAtValue: Long,
        durationValue: Long,
        noteText: String,
    ): Boolean {
        return CallNoteWriter.writeCallOrGeneral(
            context = service,
            phone = phoneNumber,
            text = noteText,
            direction = directionValue,
            callAt = callAtValue,
            durationSeconds = durationValue,
            actionIssuedAt = actionIssuedAt(),
        ).saved
    }
}

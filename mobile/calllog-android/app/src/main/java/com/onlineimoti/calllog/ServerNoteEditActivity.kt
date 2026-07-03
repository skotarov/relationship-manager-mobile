package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Native editor for a note that exists only on the Relationship Manager server. */
class ServerNoteEditActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )

        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { phone }
        val direction = intent.getStringExtra(EXTRA_DIRECTION).orEmpty()
        val callAt = intent.getLongExtra(EXTRA_CALL_AT, 0L)
        val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
        val clientEventId = intent.getStringExtra(EXTRA_SERVER_CLIENT_EVENT_ID).orEmpty().trim()
        val initialText = intent.getStringExtra(EXTRA_INITIAL_NOTE_TEXT).orEmpty()

        val noteInput = EditText(this).apply {
            setText(initialText)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            hint = "Бележка"
        }
        val saveButton = Button(this).apply { text = "Запази" }
        val cancelButton = Button(this).apply { text = "Отказ" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(TextView(this@ServerNoteEditActivity).apply {
                text = "Редактиране на бележка"
                textSize = 20f
            })
            addView(TextView(this@ServerNoteEditActivity).apply {
                text = listOf(title, PhoneCallReader.formatStartedAt(callAt), PhoneCallReader.directionLabel(direction))
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                textSize = 13f
                setPadding(0, dp(6), 0, dp(12))
            })
            addView(noteInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(LinearLayout(this@ServerNoteEditActivity).apply {
                gravity = Gravity.END
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(cancelButton)
                addView(saveButton)
            })
        }
        setContentView(root)

        cancelButton.setOnClickListener { finish() }
        saveButton.setOnClickListener {
            val queued = CallReportNoteOutbox.enqueueExistingServerNote(
                context = this,
                phone = phone,
                note = noteInput.text?.toString().orEmpty(),
                serverClientEventId = clientEventId,
                direction = direction,
                callAt = callAt,
                durationSeconds = duration,
            )
            if (!queued) {
                Toast.makeText(this, "Бележката не може да бъде записана.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBroadcast(Intent(PostCallOverlayService.ACTION_NOTES_CHANGED).setPackage(packageName))
            Toast.makeText(this, "Запазено. Синхронизирам…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PHONE = "server_note_phone"
        const val EXTRA_TITLE = "server_note_title"
        const val EXTRA_DIRECTION = "server_note_direction"
        const val EXTRA_CALL_AT = "server_note_call_at"
        const val EXTRA_DURATION = "server_note_duration"
        const val EXTRA_SERVER_CLIENT_EVENT_ID = "server_note_client_event_id"
        const val EXTRA_INITIAL_NOTE_TEXT = "server_note_initial_text"
    }
}

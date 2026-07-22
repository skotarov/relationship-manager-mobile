package com.onlineimoti.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat

class ContactNotesActivity : FontScaledActivity() {
    private val controller by lazy { ContactNotesRestoredController(this) }
    private var notesChangedReceiverRegistered = false
    private val notesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isFinishing && !isDestroyed) controller.onDataChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        controller.onCreate(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        controller.onCreate(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!notesChangedReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                notesChangedReceiver,
                IntentFilter().apply {
                    addAction(HomeActivity.ACTION_CONTACT_NOTE_SAVED)
                    addAction(PostCallOverlayService.ACTION_NOTES_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            notesChangedReceiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onResume()
    }

    override fun onStop() {
        if (notesChangedReceiverRegistered) {
            runCatching { unregisterReceiver(notesChangedReceiver) }
            notesChangedReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        controller.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BACK_TARGETS_UNFILTERED_HOME = "back_targets_unfiltered_home"
    }
}

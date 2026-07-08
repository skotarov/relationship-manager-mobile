package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle

class ContactNotesActivity : FontScaledActivity() {
    private val controller by lazy { ContactNotesRestoredController(this) }

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

    override fun onResume() {
        super.onResume()
        controller.onResume()
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

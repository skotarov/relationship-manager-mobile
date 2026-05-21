package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class NumberHistoryOptionsActivity : Activity() {
    private var phone: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phone = intent.getStringExtra(SystemCallHistoryActivity.EXTRA_PHONE).orEmpty()
        showOptions()
    }

    private fun showOptions() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        root.addView(TextView(this).apply {
            text = "История за номера"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = phone.ifBlank { "няма номер" }
            textSize = 16f
            setPadding(0, dp(8), 0, dp(14))
        })

        root.addView(actionButton("Google история / филтър") {
            openMode(SystemCallHistoryActivity.MODE_NUMBER)
        })
        root.addView(actionButton("Сърч деф. dialer") {
            openMode(SystemCallHistoryActivity.MODE_SEARCH_DEFAULT)
        })
        root.addView(actionButton("Сърч Google") {
            openMode(SystemCallHistoryActivity.MODE_SEARCH_GOOGLE)
        })
        root.addView(actionButton("Локален лог в приложението") {
            startActivity(
                Intent(this, RecentCallsActivity::class.java)
                    .putExtra(RecentCallsActivity.EXTRA_PHONE_FILTER, phone)
            )
            finish()
        })
        root.addView(actionButton("Затвори") { finish() })

        setContentView(root)
    }

    private fun openMode(mode: String) {
        startActivity(
            Intent(this, SystemCallHistoryActivity::class.java)
                .putExtra(SystemCallHistoryActivity.EXTRA_PHONE, phone)
                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, mode)
        )
        finish()
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener { action() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

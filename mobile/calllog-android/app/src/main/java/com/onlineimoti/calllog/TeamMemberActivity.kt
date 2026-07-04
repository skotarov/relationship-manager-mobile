package com.onlineimoti.calllog

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

class TeamMemberActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var email: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var create: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Покани колега"
        setContentView(content())
        if (ConfigStore.load(this).accessToken.isBlank()) {
            create.isEnabled = false
            status.text = "Първо влез във фирмения профил."
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun content(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(box)
        box.addView(TextView(this).apply {
            text = "Покани колега"
            textSize = 24f
        })
        box.addView(TextView(this).apply {
            text = "Въведи имейла на колегата. Полученият код е валиден 7 дни."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        })
        email = EditText(this).apply {
            hint = "Имейл на колегата"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        box.addView(email)
        create = MaterialButton(this).apply {
            text = "Създай покана"
            setOnClickListener { createInvitation() }
        }
        box.addView(create, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        result = TextView(this).apply {
            textSize = 18f
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        box.addView(result)
        progress = ProgressBar(this).apply { visibility = View.GONE }
        box.addView(progress)
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(status)
        return root
    }

    private fun createInvitation() {
        val address = email.text?.toString().orEmpty().trim()
        if (address.isBlank()) {
            status.text = "Въведи имейл."
            return
        }
        create.isEnabled = false
        progress.visibility = View.VISIBLE
        executor.execute {
            val call = CompanyInvitationApi.create(applicationContext, address, "member")
            runOnUiThread {
                progress.visibility = View.GONE
                create.isEnabled = true
                call.onSuccess { invitation ->
                    result.visibility = View.VISIBLE
                    result.text = invitation.code
                    status.text = "Изпрати този код на ${invitation.email.ifBlank { address }}."
                }.onFailure { error ->
                    status.text = error.message ?: "Неуспешно създаване на покана."
                }
            }
        }
    }
}

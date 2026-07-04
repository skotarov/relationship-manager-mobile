package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Allows a colleague to create only a user account linked to an email-bound invite code. */
class CompanyInviteJoinActivity : AppCompatActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var statusText: TextView
    private lateinit var submitButton: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        title = "Присъедини се към фирма"
        setContentView(createContent())
        if (ConfigStore.load(this).baseUrl.isBlank()) {
            setStatus("Преди присъединяване въведи Server URL в Настройки.")
            submitButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createContent(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun input(hint: String, inputType: Int) = EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine(true)
        }
        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(column)
        column.addView(TextView(this).apply {
            text = "Присъедини се по покана"
            textSize = 24f
        })
        column.addView(TextView(this).apply {
            text = "Поканата е безплатна за колегата. Въведи имейла, на който е изпратена, и кода от owner/admin."
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        })
        nameInput = input("Твоето име", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        emailInput = input("Поканен имейл", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordInput = input("Парола (поне 10 символа)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        codeInput = input("Код от поканата", InputType.TYPE_CLASS_TEXT)
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        column.addView(nameInput, layoutParams)
        column.addView(emailInput, layoutParams.apply { topMargin = dp(8) })
        column.addView(passwordInput, layoutParams.apply { topMargin = dp(8) })
        column.addView(codeInput, layoutParams.apply { topMargin = dp(8) })
        submitButton = MaterialButton(this).apply {
            text = "Присъедини се"
            setOnClickListener { join() }
        }
        column.addView(submitButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        val settingsButton = MaterialButton(this).apply {
            text = "Отвори настройки"
            setOnClickListener { startActivity(Intent(this@CompanyInviteJoinActivity, MainActivity::class.java)) }
        }
        column.addView(settingsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(statusText)
        return root
    }

    private fun join() {
        val name = nameInput.text?.toString().orEmpty().trim()
        val email = emailInput.text?.toString().orEmpty().trim()
        val password = passwordInput.text?.toString().orEmpty()
        val code = codeInput.text?.toString().orEmpty().trim()
        if (name.isBlank() || email.isBlank() || password.isBlank() || code.isBlank()) {
            setStatus("Попълни всички полета.")
            return
        }
        showLoading(true)
        executor.execute {
            val result = InvitedAccountApi.register(applicationContext, email, password, name, code)
            runOnUiThread {
                result.onSuccess { session ->
                    CompanyAccountApi.applySession(applicationContext, session)
                    showLoading(false)
                    setStatus("Успешно се присъедини към ${session.organizationName.ifBlank { "фирмата" }}.")
                    startActivity(Intent(this, HomeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                }.onFailure { error ->
                    showLoading(false)
                    setStatus(error.message ?: "Неуспешно присъединяване към фирмата.")
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progress.visibility = if (show) View.VISIBLE else View.GONE
        submitButton.isEnabled = !show
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }
}

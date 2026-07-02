package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityEnterpriseLoginBinding
import java.util.concurrent.Executors

class EnterpriseLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnterpriseLoginBinding
    private val loginExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (!BuildConfig.IS_PLAY_DISTRIBUTION) {
            finish()
            return
        }
        if (EnterpriseSessionStore.hasActiveSession(this)) {
            openNextStep()
            return
        }

        binding = ActivityEnterpriseLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.enterpriseLoginButton.setOnClickListener { submit() }
        binding.enterprisePasswordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onDestroy() {
        loginExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun submit() {
        val identity = binding.enterpriseIdentityInput.text?.toString().orEmpty().trim()
        val password = binding.enterprisePasswordInput.text?.toString().orEmpty()
        if (identity.isBlank() || password.isBlank()) {
            showStatus(getString(R.string.enterprise_login_error_missing_fields))
            return
        }
        setBusy(true)
        loginExecutor.execute {
            val result = runCatching { EnterpriseLoginClient.login(identity, password) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess { response ->
                    EnterpriseSessionStore.install(this, response)
                    openNextStep()
                }.onFailure { error ->
                    showStatus(error.message.orEmpty().ifBlank { "Служебният вход не бе успешен." })
                }
            }
        }
    }

    private fun openNextStep() {
        val destination = if (EnterpriseSessionStore.hasAcceptedCallLogDisclosure(this)) {
            HomeActivity::class.java
        } else {
            EnterpriseDisclosureActivity::class.java
        }
        startActivity(
            Intent(this, destination).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.enterpriseLoginButton.isEnabled = !busy
        binding.enterpriseIdentityInput.isEnabled = !busy
        binding.enterprisePasswordInput.isEnabled = !busy
        if (busy) showStatus(getString(R.string.enterprise_login_progress))
    }

    private fun showStatus(value: String) {
        binding.enterpriseLoginStatus.visibility = View.VISIBLE
        binding.enterpriseLoginStatus.text = value
    }
}

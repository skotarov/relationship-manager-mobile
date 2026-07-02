package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityEnterpriseDisclosureBinding

class EnterpriseDisclosureActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnterpriseDisclosureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        if (!BuildConfig.IS_PLAY_DISTRIBUTION) {
            finish()
            return
        }
        if (!EnterpriseSessionStore.hasActiveSession(this)) {
            openLogin()
            return
        }
        if (EnterpriseSessionStore.hasAcceptedCallLogDisclosure(this)) {
            openSettings()
            return
        }

        binding = ActivityEnterpriseDisclosureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.enterpriseDisclosureContinueButton.setOnClickListener {
            EnterpriseSessionStore.markCallLogDisclosureAccepted(this)
            openSettings()
        }
        binding.enterpriseDisclosureSignOutButton.setOnClickListener {
            EnterpriseSessionStore.clear(this)
            openLogin()
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun openLogin() {
        startActivity(
            Intent(this, EnterpriseLoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }
}

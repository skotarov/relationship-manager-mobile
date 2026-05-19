package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private var latestCall: PhoneCallRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.recentCallsButton.setOnClickListener {
            startActivity(Intent(this, RecentCallsActivity::class.java))
        }
        binding.writeLatestNoteButton.setOnClickListener {
            latestCall?.let { openPromptForCall(it) }
        }
        binding.manualNoteButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderLatestCall()
    }

    private fun renderLatestCall() {
        if (!PhoneCallReader.hasCallLogPermission(this)) {
            latestCall = null
            binding.latestCallText.text = "Липсва достъп до телефонния log. Отвори Настройки и разреши Call log."
            binding.writeLatestNoteButton.isEnabled = false
            binding.homeStatusText.text = "След разрешение тук ще виждаш последния разговор за бърза бележка."
            return
        }

        latestCall = PhoneCallReader.latestCall(this)
        val call = latestCall
        if (call == null) {
            binding.latestCallText.text = "Няма намерени разговори."
            binding.writeLatestNoteButton.isEnabled = false
            binding.homeStatusText.text = ""
            return
        }

        binding.latestCallText.text = listOf(
            call.displayName,
            call.number,
            PhoneCallReader.formatStartedAt(call.startedAt),
            PhoneCallReader.directionLabel(call.direction),
            PhoneCallReader.formatDuration(call.durationSeconds),
        ).filter { it.isNotBlank() }.joinToString(" • ")
        binding.writeLatestNoteButton.isEnabled = true
        binding.homeStatusText.text = ""
    }

    private fun openPromptForCall(call: PhoneCallRecord) {
        val config = ConfigStore.load(this)
        if (config.baseUrl.isBlank()) {
            binding.homeStatusText.text = "Първо попълни Base URL в Настройки."
            return
        }

        val formUrl = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/form.php",
            params = linkedMapOf(
                "phone" to call.number,
                "direction" to call.direction,
                "call_at" to call.startedAt.toString(),
                "duration" to call.durationSeconds.toString(),
                "access_token" to config.accessToken,
            )
        )

        startActivity(
            Intent(this, PostCallPromptActivity::class.java)
                .putExtra(PostCallPromptActivity.EXTRA_FORM_URL, formUrl)
                .putExtra(PostCallPromptActivity.EXTRA_PHONE, call.number)
                .putExtra(PostCallPromptActivity.EXTRA_DIRECTION, call.direction)
                .putExtra(PostCallPromptActivity.EXTRA_TITLE, call.displayName)
        )
    }
}

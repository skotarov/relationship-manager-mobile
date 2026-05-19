package com.onlineimoti.calllog

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityPostCallPromptBinding

class PostCallPromptActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPostCallPromptBinding
    private val handler = Handler(Looper.getMainLooper())
    private var formUrl: String = ""
    private var phone: String = ""
    private var direction: String = ""
    private var title: String = ""
    private var timeoutSeconds: Int = ConfigStore.DEFAULT_POST_CALL_TIMEOUT_SECONDS

    private val autoDismissRunnable = Runnable {
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPostCallPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        formUrl = intent.getStringExtra(EXTRA_FORM_URL).orEmpty()
        phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        direction = intent.getStringExtra(EXTRA_DIRECTION).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        timeoutSeconds = ConfigStore.load(this).postCallPromptTimeoutSeconds

        if (formUrl.isBlank()) {
            finish()
            return
        }

        binding.postCallTitleText.text = "Да запиша ли бележка?"
        binding.postCallSubtitleText.text = buildSubtitle()

        binding.writeNoteButton.setOnClickListener {
            handler.removeCallbacks(autoDismissRunnable)
            startActivity(
                Intent(this, WebViewActivity::class.java)
                    .putExtra(WebViewActivity.EXTRA_URL, formUrl)
                    .putExtra(WebViewActivity.EXTRA_PHONE, phone)
                    .putExtra(WebViewActivity.EXTRA_DIRECTION, direction)
            )
            finish()
        }

        binding.skipNoteButton.setOnClickListener {
            handler.removeCallbacks(autoDismissRunnable)
            finish()
        }

        handler.postDelayed(autoDismissRunnable, timeoutSeconds * 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismissRunnable)
        super.onDestroy()
    }

    private fun buildSubtitle(): String {
        val person = title.ifBlank { phone }
        val directionLabel = when (direction) {
            "in" -> "входящ разговор"
            "out" -> "изходящ разговор"
            else -> "разговор"
        }
        return listOf(person, directionLabel, "затваря се след ${timeoutSeconds} сек")
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }

    companion object {
        const val EXTRA_FORM_URL = "form_url"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_TITLE = "title"
    }
}

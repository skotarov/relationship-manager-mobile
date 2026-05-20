package com.onlineimoti.calllog

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.onlineimoti.calllog.databinding.ActivityPostCallPromptBinding

class PostCallPromptActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPostCallPromptBinding
    private val handler = Handler(Looper.getMainLooper())
    private var finishRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        binding = ActivityPostCallPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val phone = intent.getStringExtra(EXTRA_PHONE).orEmpty()
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { phone }
        val formUrl = intent.getStringExtra(EXTRA_FORM_URL).orEmpty()
        val timeoutSeconds = intent.getIntExtra(
            EXTRA_TIMEOUT_SECONDS,
            AppConfig.DEFAULT_POST_CALL_AUTO_CLOSE_SECONDS
        ).coerceAtLeast(1)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        if (notificationId > 0) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }

        binding.promptTitle.text = getString(R.string.post_call_prompt_title)
        binding.promptSubtitle.text = getString(R.string.post_call_prompt_body, displayName)
        binding.promptMeta.text = getString(R.string.post_call_prompt_timeout, timeoutSeconds)

        binding.saveNoteButton.setOnClickListener {
            if (formUrl.isNotBlank()) {
                startActivity(WebViewActivity.intent(this, formUrl))
            }
            finish()
        }

        binding.skipButton.setOnClickListener {
            finish()
        }

        finishRunnable = Runnable { finish() }.also { runnable ->
            handler.postDelayed(runnable, timeoutSeconds * 1000L)
        }
    }

    override fun onDestroy() {
        finishRunnable?.let(handler::removeCallbacks)
        finishRunnable = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_FORM_URL = "form_url"
        const val EXTRA_TIMEOUT_SECONDS = "timeout_seconds"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

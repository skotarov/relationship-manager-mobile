package com.onlineimoti.calllog

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * SMS compose bridge. When Relationship Manager is the default SMS app, this screen keeps the
 * decision explicit: open CRM history here or continue the draft in the SMS app that was default
 * before RM took over.
 */
class CommunicationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCommunicationIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCommunicationIntent(intent)
    }

    private fun handleCommunicationIntent(sourceIntent: Intent?) {
        val model = CommunicationModel.from(sourceIntent)
        if (model == null) {
            Toast.makeText(this, "Не разпознах SMS или e-mail адрес", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(buildContent(model))
    }

    private fun buildContent(model: CommunicationModel): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }

        root.addView(TextView(this).apply {
            text = if (model.kind == CommunicationKind.SMS) "SMS в Call Report" else "E-mail в Call Report"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(15, 23, 42))
        })

        root.addView(TextView(this).apply {
            text = if (model.kind == CommunicationKind.SMS) "Получател: ${model.recipient}" else "До: ${model.recipient}"
            textSize = 15f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(12), 0, 0)
        })

        if (model.subject.isNotBlank()) {
            root.addView(detailCard("Тема", model.subject))
        }
        if (model.body.isNotBlank()) {
            root.addView(detailCard("Текст", model.body))
        }

        if (model.kind == CommunicationKind.SMS && model.normalizedPhone.isNotBlank()) {
            root.addView(primaryButton("Остани в Call Report") {
                openContactHistory(model)
            }.apply { topMargin(dp(18)) })
        }

        root.addView(secondaryButton(if (model.kind == CommunicationKind.SMS) "Продължи към SMS приложение" else "Продължи към e-mail приложение") {
            forwardToExternalComposer(model)
        }.apply { topMargin(dp(10)) })

        root.addView(secondaryButton("Затвори") { finish() }.apply { topMargin(dp(8)) })

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(248, 250, 252))
            addView(root)
        }
    }

    private fun openContactHistory(model: CommunicationModel) {
        val phone = model.normalizedPhone
        if (phone.isBlank()) {
            Toast.makeText(this, "Не намерих телефонен номер", Toast.LENGTH_SHORT).show()
            return
        }
        val title = RmRealContactLookup.resolveDisplayName(this, phone).orEmpty().ifBlank { phone }
        startActivity(
            Intent(this, ContactNotesActivity::class.java)
                .putExtra(ContactNotesActivity.EXTRA_PHONE, phone)
                .putExtra(ContactNotesActivity.EXTRA_TITLE, title)
        )
        finish()
    }

    private fun forwardToExternalComposer(model: CommunicationModel) {
        if (model.kind == CommunicationKind.SMS) {
            val opened = SmsExternalAppStore.openExternalSmsComposer(this, model.recipient, model.body)
            if (opened) {
                finish()
            } else {
                Toast.makeText(this, "Няма намерено друго SMS приложение", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val target = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (model.recipient.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(model.recipient))
            if (model.subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, model.subject)
            if (model.body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, model.body)
        }

        val ownComponent = ComponentName(this, CommunicationActionActivity::class.java)
        val chooser = Intent.createChooser(target, "Избери e-mail приложение").apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ownComponent))
        }
        runCatching {
            startActivity(chooser)
            finish()
        }.onFailure {
            Toast.makeText(this, "Няма намерено подходящо приложение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detailCard(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label\n$value"
            textSize = 14.5f
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(203, 213, 225), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
    }

    private fun primaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRect(Color.rgb(15, 23, 42), dp(12), Color.TRANSPARENT, 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            )
        }
    }

    private fun secondaryButton(textValue: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
            setTextColor(Color.rgb(15, 23, 42))
            textSize = 15f
            background = roundedRect(Color.WHITE, dp(12), Color.rgb(148, 163, 184), dp(1))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            )
        }
    }

    private fun Button.topMargin(value: Int) {
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = value
    }

    private fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class CommunicationKind {
        SMS,
        EMAIL,
    }

    private data class CommunicationModel(
        val kind: CommunicationKind,
        val recipient: String,
        val normalizedPhone: String = "",
        val subject: String = "",
        val body: String = "",
    ) {
        companion object {
            fun from(intent: Intent?): CommunicationModel? {
                val uri = intent?.data ?: return null
                return when (uri.scheme.orEmpty().lowercase()) {
                    "sms", "smsto", "mms", "mmsto" -> smsModel(intent, uri)
                    "mailto" -> emailModel(intent, uri)
                    else -> null
                }
            }

            private fun smsModel(intent: Intent, uri: Uri): CommunicationModel? {
                val rawRecipient = Uri.decode(uri.schemeSpecificPart.orEmpty().substringBefore('?')).trim()
                val normalizedPhone = PhoneNormalizer.normalize(rawRecipient)
                if (normalizedPhone.isBlank()) return null
                return CommunicationModel(
                    kind = CommunicationKind.SMS,
                    recipient = rawRecipient.ifBlank { normalizedPhone },
                    normalizedPhone = normalizedPhone,
                    subject = intent.stringExtra("subject").ifBlank { uriQueryValue(uri, "subject") },
                    body = intent.stringExtra("sms_body")
                        .ifBlank { intent.stringExtra(Intent.EXTRA_TEXT) }
                        .ifBlank { uriQueryValue(uri, "body") },
                )
            }

            private fun emailModel(intent: Intent, uri: Uri): CommunicationModel? {
                val uriRecipient = Uri.decode(uri.schemeSpecificPart.orEmpty().substringBefore('?')).trim()
                val extraRecipient = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                val recipient = uriRecipient.ifBlank { extraRecipient }
                if (recipient.isBlank()) return null
                return CommunicationModel(
                    kind = CommunicationKind.EMAIL,
                    recipient = recipient,
                    subject = intent.stringExtra(Intent.EXTRA_SUBJECT).ifBlank { uriQueryValue(uri, "subject") },
                    body = intent.stringExtra(Intent.EXTRA_TEXT).ifBlank { uriQueryValue(uri, "body") },
                )
            }

            private fun uriQueryValue(uri: Uri, key: String): String {
                val query = uri.schemeSpecificPart.orEmpty().substringAfter('?', missingDelimiterValue = "")
                if (query.isBlank()) return ""
                return query.split('&')
                    .firstOrNull { part -> part.substringBefore('=').equals(key, ignoreCase = true) }
                    ?.substringAfter('=', missingDelimiterValue = "")
                    ?.let(Uri::decode)
                    .orEmpty()
                    .trim()
            }

            private fun Intent.stringExtra(key: String): String = getStringExtra(key).orEmpty().trim()
        }
    }
}

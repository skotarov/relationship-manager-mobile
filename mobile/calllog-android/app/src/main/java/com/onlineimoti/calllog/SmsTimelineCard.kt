package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * Shared visual template for every SMS row in the application.
 *
 * Keeping the icon, metadata, contact name and body in one place prevents the
 * device SMS list, contact timeline and filtered full history from drifting apart.
 */
internal object SmsTimelineCard {
    data class Action(
        val drawableRes: Int,
        val contentDescription: String,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    )

    data class Colors(
        val background: Int,
        val border: Int,
        val title: Int,
        val meta: Int,
        val body: Int,
    )

    fun create(
        activity: Activity,
        dp: (Int) -> Int,
        message: PhoneCallRecord,
        displayName: CharSequence = message.displayName,
        metaText: CharSequence? = null,
        bodyText: CharSequence = message.smsBody,
        showTitle: Boolean = true,
        maxBodyLines: Int = 3,
        actions: List<Action> = emptyList(),
        beforeBody: ((LinearLayout) -> Unit)? = null,
        afterBody: ((LinearLayout) -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        colors: Colors? = null,
        metaTrailingIconRes: Int = 0,
    ): MaterialCardView {
        val palette = colors ?: Colors(
            background = activity.getColor(R.color.calllog_surface),
            border = activity.getColor(R.color.calllog_border),
            title = activity.getColor(R.color.calllog_text),
            meta = activity.getColor(R.color.calllog_muted_text),
            body = activity.getColor(R.color.calllog_text),
        )
        val outgoing = isOutgoing(message)
        val title = displayName.toString().trim().ifBlank { message.number }
        val resolvedMeta = metaText ?: defaultMeta(message, title, outgoing)

        return MaterialCardView(activity).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(palette.border)
            setCardBackgroundColor(palette.background)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))

                addView(ImageView(activity).apply {
                    setImageResource(if (outgoing) R.drawable.ic_sms_bubble_left else R.drawable.ic_sms_bubble_right)
                    contentDescription = directionLabel(outgoing)
                    scaleType = ImageView.ScaleType.CENTER
                }, LinearLayout.LayoutParams(dp(32), dp(36)).apply { marginEnd = dp(6) })

                val textColumn = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textColumn.addView(TextView(activity).apply {
                    text = resolvedMeta
                    textSize = 12.5f
                    setTextColor(palette.meta)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    if (metaTrailingIconRes != 0) {
                        setCompoundDrawablesWithIntrinsicBounds(0, 0, metaTrailingIconRes, 0)
                        compoundDrawablePadding = dp(6)
                    }
                })
                if (showTitle) {
                    textColumn.addView(TextView(activity).apply {
                        text = title
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(palette.title)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                beforeBody?.invoke(textColumn)
                textColumn.addView(TextView(activity).apply {
                    text = bodyText.toString().ifBlank { activity.getString(R.string.dynamic_sms_empty_body) }
                    textSize = 13f
                    setTextColor(palette.body)
                    maxLines = maxBodyLines.coerceAtLeast(1)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                afterBody?.invoke(textColumn)
                addView(textColumn)

                if (actions.isNotEmpty()) {
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { leftMargin = dp(3) }
                        actions.forEach { action ->
                            addView(ImageButton(activity).apply {
                                setImageResource(action.drawableRes)
                                contentDescription = action.contentDescription
                                background = null
                                setBackgroundColor(Color.TRANSPARENT)
                                scaleType = ImageView.ScaleType.CENTER
                                setPadding(dp(6), dp(6), dp(6), dp(6))
                                isEnabled = action.enabled
                                alpha = if (action.enabled) 1f else 0.38f
                                layoutParams = LinearLayout.LayoutParams(dp(32), dp(36))
                                setOnClickListener { if (action.enabled) action.onClick() }
                            })
                        }
                    })
                }
            })
        }
    }

    fun directionLabel(message: PhoneCallRecord): String = directionLabel(isOutgoing(message))

    private fun defaultMeta(message: PhoneCallRecord, title: String, outgoing: Boolean): String {
        return listOf(
            PhoneCallReader.formatStartedAt(message.startedAt),
            directionLabel(outgoing),
            message.number.takeIf { title.isNotBlank() && title != message.number },
        ).filter { !it.isNullOrBlank() }.joinToString(" • ")
    }

    private fun directionLabel(outgoing: Boolean): String = when {
        outgoing && AppLocaleText.isBulgarian() -> "изпратено"
        !outgoing && AppLocaleText.isBulgarian() -> "получено"
        outgoing -> "sent"
        else -> "received"
    }

    private fun isOutgoing(message: PhoneCallRecord): Boolean {
        return message.direction == "sms_out" || message.direction == "out"
    }
}

package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class PostCallOverlayUi(private val context: Context) {
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun roundedRect(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    fun shadowScroll(card: View): ScrollView {
        return ScrollView(context).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipToPadding = false
            clipChildren = false
            addView(card)
        }
    }

    fun stylePopupCard(view: View) {
        view.background = roundedRect(Color.WHITE, dp(24), Color.TRANSPARENT, 0)
        view.clipToOutline = true
        view.elevation = dp(11).toFloat()
        view.translationZ = dp(3).toFloat()
    }

    fun noteRightAction(onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(R.drawable.ic_chat_note)
            contentDescription = "Добави бележка"
            background = roundedRect(Color.WHITE, dp(22), Color.rgb(75, 85, 99), dp(1))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(45), dp(45)).apply {
                marginStart = dp(10)
                topMargin = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    fun notePreviewRow(noteText: String, textColor: Int, backgroundColor: Int, strokeColor: Int, topMargin: Int, iconRes: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            if (backgroundColor != Color.TRANSPARENT) {
                setPadding(dp(8), dp(7), dp(10), dp(7))
                background = roundedRect(backgroundColor, dp(10), strokeColor, dp(1))
            } else {
                setPadding(0, 0, 0, 0)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    marginEnd = dp(6)
                    this.topMargin = dp(1)
                }
            })
            addView(TextView(context).apply {
                text = noteText
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    fun callNoteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return noteEditText(value, hintText, minLineCount, topMargin).apply {
            setTextColor(NoteUiStyle.Call.text)
            setHintTextColor(NoteUiStyle.Call.metaText)
            background = roundedRect(NoteUiStyle.Call.background, dp(12), NoteUiStyle.Call.border, dp(1))
        }
    }

    fun noteEditText(value: String, hintText: String, minLineCount: Int, topMargin: Int): EditText {
        return EditText(context).apply {
            setText(value)
            hint = hintText
            minLines = minLineCount
            maxLines = 5
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            setHintTextColor(Color.rgb(107, 114, 128))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedRect(Color.rgb(249, 250, 251), dp(12), Color.rgb(209, 213, 219), dp(1))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }
        }
    }

    fun iconAction(drawableRes: Int, action: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            background = roundedRect(Color.rgb(243, 244, 246), dp(18), Color.TRANSPARENT, 0)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(35), dp(35)).apply { marginStart = dp(5) }
            setOnClickListener { action() }
        }
    }

    fun textAction(textValue: String, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = textValue
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedRect(Color.rgb(55, 65, 81), dp(12), Color.TRANSPARENT, 0)
            clipToOutline = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { action() }
        }
    }

    fun secondaryTextAction(textValue: String, action: () -> Unit): TextView {
        return TextView(context).apply {
            text = textValue
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            background = roundedRect(Color.rgb(243, 244, 246), dp(12), Color.TRANSPARENT, 0)
            clipToOutline = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { action() }
        }
    }

    fun secondaryIconAction(drawableRes: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(drawableRes)
            contentDescription = description
            background = roundedRect(Color.rgb(243, 244, 246), dp(12), Color.TRANSPARENT, 0)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            setOnClickListener { action() }
        }
    }
}

package com.onlineimoti.calllog

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Small in-app number keypad. The selected number is returned to Home for dialing. */
class NumberEntryActivity : Activity() {
    private var value = ""
    private lateinit var valueView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applyFromConfig(this)
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(248, 250, 252))

            addView(LinearLayout(this@NumberEntryActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@NumberEntryActivity).apply {
                    text = "Набери номер"
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(30, 41, 59))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@NumberEntryActivity).apply {
                    text = "×"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(71, 85, 105))
                    background = rounded(Color.rgb(241, 245, 249), dp(22))
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { finish() }
                })
            })

            valueView = TextView(this@NumberEntryActivity).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                minLines = 1
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(15, 23, 42))
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(Color.WHITE, dp(14), Color.rgb(203, 213, 225), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(68),
                ).apply { topMargin = dp(20); bottomMargin = dp(16) }
            }
            addView(valueView)
            renderValue()

            addView(keyRow(listOf("1" to "", "2" to "ABC", "3" to "DEF")))
            addView(keyRow(listOf("4" to "GHI", "5" to "JKL", "6" to "MNO")))
            addView(keyRow(listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ")))
            addView(keyRow(listOf("*" to "", "0" to "+", "#" to "")))

            addView(LinearLayout(this@NumberEntryActivity).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(TextView(this@NumberEntryActivity).apply {
                    text = "⌫"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(71, 85, 105))
                    background = rounded(Color.WHITE, dp(28), Color.rgb(203, 213, 225), dp(1))
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(20) }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { removeLast() }
                    setOnLongClickListener { clearAll(); true }
                })
                addView(ImageButton(this@NumberEntryActivity).apply {
                    setImageResource(R.drawable.ic_phone_call)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(15), dp(15), dp(15), dp(15))
                    background = rounded(Color.rgb(37, 150, 190), dp(30))
                    contentDescription = "Набери"
                    layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
                    setOnClickListener { confirmNumber() }
                })
            })
        }
    }

    private fun keyRow(keys: List<Pair<String, String>>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66),
            ).apply { topMargin = dp(4) }
            keys.forEach { (digit, letters) ->
                addView(keyView(digit, letters), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        }
    }

    private fun keyView(digit: String, letters: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, dp(14))
            setOnClickListener { append(digit) }
            if (digit == "0") {
                setOnLongClickListener { append("+"); true }
            }
            addView(TextView(this@NumberEntryActivity).apply {
                text = digit
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(30, 41, 59))
            })
            addView(TextView(this@NumberEntryActivity).apply {
                text = letters
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(100, 116, 139))
                minHeight = dp(12)
            })
        }
    }

    private fun append(part: String) {
        value += part
        renderValue()
    }

    private fun removeLast() {
        if (value.isEmpty()) return
        value = value.dropLast(1)
        renderValue()
    }

    private fun clearAll() {
        value = ""
        renderValue()
    }

    private fun renderValue() {
        valueView.text = value
    }

    private fun confirmNumber() {
        if (value.isBlank()) {
            Toast.makeText(this, "Въведи номер", Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_NUMBER, value))
        finish()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (stroke != null && strokeWidth > 0) setStroke(strokeWidth, stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NUMBER = "number"
    }
}

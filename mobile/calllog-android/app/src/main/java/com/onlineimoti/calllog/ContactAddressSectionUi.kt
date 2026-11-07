package com.onlineimoti.calllog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Contact details address block; unknown-number values use the local/server address store. */
internal class ContactAddressSectionUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    fun add(
        root: LinearLayout,
        phone: String,
        openSystemContact: () -> Unit,
        onChanged: () -> Unit,
    ) {
        val context = activity.applicationContext
        val inAddressBook = ContactAddressReader.isAndroidContact(context, phone)
        val addresses = ContactAddressReader.addressesForPhone(context, phone)
        if (addresses.isEmpty() && inAddressBook) return
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = AppModalStyle.secondary(activity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(activity).apply {
            text = "Адрес${if (addresses.size == 1) "" else "и"}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(2))
        })
        addresses.forEach { address -> card.addView(addressRow(address)) }
        card.addView(TextView(activity).apply {
            text = if (inAddressBook) "Редакция в Контакти" else if (addresses.isEmpty()) "Добави адрес" else "Редактирай адреси"
            textSize = 12.5f
            setTextColor(AppModalStyle.accent(activity))
            gravity = Gravity.END
            setPadding(0, dp(5), 0, 0)
            isClickable = true
            setOnClickListener {
                if (inAddressBook) openSystemContact() else editUnknown(phone, addresses, onChanged)
            }
        })
        root.addView(card, 1)
    }

    private fun addressRow(address: String): TextView = TextView(activity).apply {
        text = address
        textSize = 14f
        setTextColor(activity.getColor(R.color.calllog_text))
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_location, 0, 0, 0)
        compoundDrawablePadding = dp(6)
        setPadding(0, dp(4), 0, dp(4))
        isClickable = true
        setOnClickListener {
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}")))
            }.onFailure { Toast.makeText(activity, "Няма приложение за навигация.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun editUnknown(phone: String, current: List<String>, onChanged: () -> Unit) {
        val input = EditText(activity).apply {
            setText(current.joinToString("\n"))
            hint = "По един адрес на ред"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(activity)
            .setTitle("Адреси")
            .setMessage("Тези адреси се пазят локално и се синхронизират със сървъра.")
            .setView(input)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Запази") { _, _ ->
                if (ContactAddressReader.saveUnknownAddresses(activity, phone, input.text?.toString().orEmpty().lineSequence().toList())) {
                    onChanged()
                }
            }
            .show()
    }
}

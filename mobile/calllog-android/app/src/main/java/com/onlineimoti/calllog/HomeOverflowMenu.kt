package com.onlineimoti.calllog

import android.content.Intent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Keeps HomeActivity focused on state coordination rather than menu plumbing. */
internal object HomeOverflowMenu {
    fun show(activity: AppCompatActivity, anchor: View, openSettings: () -> Unit) {
        show(
            activity = activity,
            anchor = anchor,
            openSettings = openSettings,
            openCompanyAccount = {
                activity.startActivity(Intent(activity, CompanyAccountActivity::class.java))
            },
        )
    }

    fun show(
        activity: AppCompatActivity,
        anchor: View,
        openSettings: () -> Unit,
        openCompanyAccount: () -> Unit,
    ) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MENU_PHONE_CALL_LOG, 10, activity.getString(R.string.home_overflow_phone_log))
            menu.add(0, MENU_COMPANY_ACCOUNT, 20, "Фирмен профил и лиценз")
            menu.add(0, MENU_JOIN_COMPANY, 30, "Присъедини се по покана")
            menu.add(0, MENU_INVITE_COLLEAGUE, 40, "Покани колега")
            menu.add(0, MENU_SETTINGS, 50, activity.getString(R.string.home_overflow_settings))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_PHONE_CALL_LOG -> {
                        activity.startActivity(
                            Intent(activity, SystemCallHistoryActivity::class.java)
                                .putExtra(SystemCallHistoryActivity.EXTRA_MODE, SystemCallHistoryActivity.MODE_GENERAL),
                        )
                        true
                    }
                    MENU_COMPANY_ACCOUNT -> {
                        openCompanyAccount()
                        true
                    }
                    MENU_JOIN_COMPANY -> {
                        showJoinDialog(activity)
                        true
                    }
                    MENU_INVITE_COLLEAGUE -> {
                        showInviteDialog(activity)
                        true
                    }
                    MENU_SETTINGS -> {
                        openSettings()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showJoinDialog(activity: AppCompatActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun input(hint: String, inputType: Int) = EditText(activity).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine(true)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val name = input("Твоето име", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val email = input("Поканен имейл", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = input("Парола (поне 10 символа)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val code = input("Код от поканата", InputType.TYPE_CLASS_TEXT)
        container.addView(name)
        container.addView(email)
        container.addView(password)
        container.addView(code)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Присъедини се по покана")
            .setMessage("Поканата е безплатна. Имейлът трябва да съвпада с този, на който owner/admin е създал поканата.")
            .setView(container)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Присъедини се", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val safeName = name.text?.toString().orEmpty().trim()
                val safeEmail = email.text?.toString().orEmpty().trim()
                val safePassword = password.text?.toString().orEmpty()
                val safeCode = code.text?.toString().orEmpty().trim()
                if (safeName.isBlank() || safeEmail.isBlank() || safePassword.isBlank() || safeCode.isBlank()) {
                    dialog.setMessage("Попълни всички полета.")
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = InvitedAccountApi.register(
                        activity.applicationContext,
                        safeEmail,
                        safePassword,
                        safeName,
                        safeCode,
                    )
                    activity.runOnUiThread {
                        result.onSuccess { session ->
                            CompanyAccountApi.applySession(activity.applicationContext, session)
                            dialog.dismiss()
                            activity.startActivity(
                                Intent(activity, HomeActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                        }.onFailure { error ->
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.setMessage(error.message ?: "Неуспешно присъединяване към фирмата.")
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showInviteDialog(activity: AppCompatActivity) {
        val config = ConfigStore.load(activity)
        if (!CorporateAccess.isActive(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Покани колега")
                .setMessage("Първо влез във фирмения профил.")
                .setPositiveButton("Фирмен профил") { _, _ ->
                    activity.startActivity(Intent(activity, CompanyAccountActivity::class.java))
                }
                .setNegativeButton("Отказ", null)
                .show()
            return
        }
        val email = EditText(activity).apply {
            hint = "Имейл на колегата"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            val horizontal = (activity.resources.displayMetrics.density * 24).toInt()
            setPadding(horizontal, 0, horizontal, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Покани колега")
            .setMessage("Ще се създаде 7-дневен код, валиден само за този имейл.")
            .setView(email)
            .setNegativeButton("Отказ", null)
            .setPositiveButton("Създай покана", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val safeEmail = email.text?.toString().orEmpty().trim()
                if (safeEmail.isBlank()) {
                    dialog.setMessage("Въведи имейл на колегата.")
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = CompanyInvitationApi.create(activity.applicationContext, safeEmail, "member")
                    activity.runOnUiThread {
                        result.onSuccess { invitation ->
                            dialog.dismiss()
                            AlertDialog.Builder(activity)
                                .setTitle("Поканата е готова")
                                .setMessage("Изпрати този код на ${invitation.email.ifBlank { safeEmail }}:\n\n${invitation.code}\n\nКодът е валиден 7 дни.")
                                .setPositiveButton("Готово", null)
                                .show()
                        }.onFailure { error ->
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.setMessage(error.message ?: "Неуспешно създаване на покана.")
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private const val MENU_PHONE_CALL_LOG = 1
    private const val MENU_COMPANY_ACCOUNT = 2
    private const val MENU_JOIN_COMPANY = 3
    private const val MENU_INVITE_COLLEAGUE = 4
    private const val MENU_SETTINGS = 5
}

package com.onlineimoti.calllog

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService

/** Wires the Debug › Server tests subsection without exposing credentials in the UI. */
internal class MainServerTestsController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val executor: ExecutorService,
    private val saveConfig: () -> AppConfig,
    private val setStatus: (String) -> Unit,
) {
    private val resultsView: TextView by lazy { activity.findViewById(R.id.serverTestResultsText) }
    private val allowWrites: CheckBox by lazy { activity.findViewById(R.id.allowServerWriteTestsCheckBox) }

    fun wire() {
        button(R.id.testServerAllButton).setOnClickListener {
            execute("Тествам сървъра…") { context, config, phone, direction ->
                ServerDebugTestActions.runAll(context, config, phone, direction, allowWrites.isChecked)
            }
        }
        button(R.id.testServerConfigButton).setOnClickListener {
            execute("Тествам config.php…") { _, config, _, _ -> listOf(ServerDebugTestActions.testConfig(config)) }
        }
        button(R.id.testServerLookupButton).setOnClickListener {
            execute("Тествам lookup.php…") { _, config, phone, direction -> listOf(ServerDebugTestActions.testLookup(config, phone, direction)) }
        }
        button(R.id.testServerNotesButton).setOnClickListener {
            execute("Тествам notes_lookup.php…") { _, config, phone, _ -> listOf(ServerDebugTestActions.testNotesLookup(config, phone)) }
        }
        button(R.id.testServerHistoryLookupButton).setOnClickListener {
            execute("Тествам history_lookup.php…") { _, config, phone, direction -> listOf(ServerDebugTestActions.testHistoryLookup(config, phone, direction)) }
        }
        button(R.id.testServerPropertySearchButton).setOnClickListener {
            execute("Тествам property_search.php…") { _, config, phone, _ -> listOf(ServerDebugTestActions.testPropertySearch(config, phone)) }
        }
        button(R.id.testServerSyncButton).setOnClickListener {
            if (!allowWrites.isChecked) {
                render(listOf(ServerDebugTestResult("sync.php", false, "включи „Разреши тестови записи на сървъра“")))
            } else {
                execute("Тествам sync.php…") { context, config, phone, direction ->
                    listOf(ServerDebugTestActions.testSync(context, config, phone, direction))
                }
            }
        }
        button(R.id.testServerSubmitButton).setOnClickListener {
            if (!allowWrites.isChecked) {
                render(listOf(ServerDebugTestResult("submit.php", false, "включи „Разреши тестови записи на сървъра“")))
            } else {
                execute("Тествам submit.php…") { context, config, phone, direction ->
                    listOf(ServerDebugTestActions.testSubmit(context, config, phone, direction))
                }
            }
        }
        button(R.id.testServerFormButton).setOnClickListener { openForm() }
        button(R.id.testServerHistoryButton).setOnClickListener { openHistory() }
    }

    private fun openForm() {
        val config = saveConfig()
        val phone = testPhone()
        if (missingRequired(config, phone)) return
        val url = ServerDebugTestActions.buildFormUrl(activity, config, phone, direction())
        setStatus("Отварям form.php с текущите server настройки.")
        PostCallActionRouter.openRemoteForm(activity, url, phone, direction())
    }

    private fun openHistory() {
        val config = saveConfig()
        val phone = testPhone()
        if (missingRequired(config, phone)) return
        val url = ServerDebugTestActions.buildHistoryUrl(config, phone)
        setStatus("Отварям history.php с текущите server настройки.")
        PostCallActionRouter.openRemoteForm(activity, url, phone, direction())
    }

    private fun execute(
        status: String,
        work: (Context, AppConfig, String, String) -> List<ServerDebugTestResult>,
    ) {
        val config = saveConfig()
        val phone = testPhone()
        if (missingRequired(config, phone)) return
        val direction = direction()
        resultsView.text = "⏳ $status"
        resultsView.visibility = View.VISIBLE
        setStatus(status)
        executor.execute {
            val results = runCatching { work(activity.applicationContext, config, phone, direction) }
                .getOrElse { error -> listOf(ServerDebugTestResult("Сървър", false, error.message.orEmpty().ifBlank { "неуспешен тест" })) }
            activity.runOnUiThread { render(results) }
        }
    }

    private fun render(results: List<ServerDebugTestResult>) {
        val passed = results.count { it.success }
        val failed = results.size - passed
        resultsView.text = results.joinToString("\n") { result ->
            val icon = if (result.success) "✓" else "✕"
            "$icon ${result.label}: ${result.detail}"
        }
        resultsView.visibility = View.VISIBLE
        setStatus("Тест на сървъра: $passed успешни, $failed неуспешни.")
    }

    private fun missingRequired(config: AppConfig, phone: String): Boolean {
        val message = when {
            !config.remoteEnabled -> "Включи „Сървър“ в Server settings."
            config.baseUrl.isBlank() -> "Липсва Base URL."
            config.accessToken.isBlank() -> "Липсва Access token."
            phone.isBlank() -> "Въведи тестов телефон."
            else -> ""
        }
        if (message.isBlank()) return false
        render(listOf(ServerDebugTestResult("Сървър", false, message)))
        return true
    }

    private fun testPhone(): String = binding.testsSection.phoneInput.text?.toString().orEmpty().trim().ifBlank { "0877904903" }
    private fun direction(): String = if (binding.testsSection.directionIn.isChecked) "in" else "out"
    private fun button(id: Int): MaterialButton = activity.findViewById(id)
}

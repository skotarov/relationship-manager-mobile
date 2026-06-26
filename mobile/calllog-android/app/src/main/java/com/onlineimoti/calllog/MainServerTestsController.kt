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
            execute(activity.getString(R.string.server_test_progress, activity.getString(R.string.server_test_server))) { context, config, phone, direction ->
                ServerDebugTestActions.runAll(context, config, phone, direction, allowWrites.isChecked)
            }
        }
        button(R.id.testServerConfigButton).setOnClickListener {
            execute(activity.getString(R.string.server_test_progress, "config.php")) { _, config, _, _ -> listOf(ServerDebugTestActions.testConfig(config)) }
        }
        button(R.id.testServerLookupButton).setOnClickListener {
            execute(activity.getString(R.string.server_test_progress, "lookup.php")) { _, config, phone, direction -> listOf(ServerDebugTestActions.testLookup(config, phone, direction)) }
        }
        button(R.id.testServerNotesButton).setOnClickListener {
            execute(activity.getString(R.string.server_test_progress, "notes_lookup.php")) { _, config, phone, _ -> listOf(ServerDebugTestActions.testNotesLookup(config, phone)) }
        }
        button(R.id.testServerHistoryLookupButton).setOnClickListener {
            execute(activity.getString(R.string.server_test_progress, "history_lookup.php")) { _, config, phone, direction -> listOf(ServerDebugTestActions.testHistoryLookup(config, phone, direction)) }
        }
        button(R.id.testServerPropertySearchButton).setOnClickListener {
            execute(activity.getString(R.string.server_test_progress, "property_search.php")) { _, config, phone, _ -> listOf(ServerDebugTestActions.testPropertySearch(config, phone)) }
        }
        button(R.id.testServerSyncButton).setOnClickListener {
            if (!allowWrites.isChecked) {
                render(listOf(ServerDebugTestResult("sync.php", false, activity.getString(R.string.server_test_enable_write_records))))
            } else {
                execute(activity.getString(R.string.server_test_progress, "sync.php")) { context, config, phone, direction ->
                    listOf(ServerDebugTestActions.testSync(context, config, phone, direction))
                }
            }
        }
        button(R.id.testServerSubmitButton).setOnClickListener {
            if (!allowWrites.isChecked) {
                render(listOf(ServerDebugTestResult("submit.php", false, activity.getString(R.string.server_test_enable_write_records))))
            } else {
                execute(activity.getString(R.string.server_test_progress, "submit.php")) { context, config, phone, direction ->
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
        setStatus(activity.getString(R.string.server_test_open_form))
        PostCallActionRouter.openRemoteForm(activity, url, phone, direction())
    }

    private fun openHistory() {
        val config = saveConfig()
        val phone = testPhone()
        if (missingRequired(config, phone)) return
        val url = ServerDebugTestActions.buildHistoryUrl(config, phone)
        setStatus(activity.getString(R.string.server_test_open_history))
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
                .getOrElse { error ->
                    listOf(
                        ServerDebugTestResult(
                            activity.getString(R.string.server_test_server),
                            false,
                            error.message.orEmpty().ifBlank { activity.getString(R.string.server_test_failed) },
                        ),
                    )
                }
            activity.runOnUiThread { render(results) }
        }
    }

    private fun render(results: List<ServerDebugTestResult>) {
        val passed = results.count { it.success }
        val failed = results.size - passed
        resultsView.text = results.joinToString("\n") { result ->
            val icon = if (result.success) "✓" else "✕"
            "$icon ${localizeServerText(result.label)}: ${localizeServerText(result.detail)}"
        }
        resultsView.visibility = View.VISIBLE
        setStatus(activity.getString(R.string.server_test_summary, passed, failed))
    }

    private fun missingRequired(config: AppConfig, phone: String): Boolean {
        val message = when {
            !config.remoteEnabled -> activity.getString(R.string.server_test_enable_server)
            config.baseUrl.isBlank() -> activity.getString(R.string.server_test_missing_base_url)
            config.accessToken.isBlank() -> activity.getString(R.string.server_test_missing_access_token)
            phone.isBlank() -> activity.getString(R.string.server_test_missing_phone)
            else -> ""
        }
        if (message.isBlank()) return false
        render(listOf(ServerDebugTestResult(activity.getString(R.string.server_test_server), false, message)))
        return true
    }

    private fun localizeServerText(value: String): String {
        val trimmed = value.trim()
        when (trimmed) {
            "Сървър" -> return activity.getString(R.string.server_test_server)
            "sync.php (тестов запис)" -> return activity.getString(R.string.server_test_sync_write_label)
            "submit.php (тестова бележка)" -> return activity.getString(R.string.server_test_submit_write_label)
            "пропуснат — тестовите записи не са разрешени" -> return activity.getString(R.string.server_test_skipped_writes)
            "включи „Разреши тестови записи на сървъра“" -> return activity.getString(R.string.server_test_enable_write_records)
            "включи „Сървър“ в настройките" -> return activity.getString(R.string.server_test_enable_server)
            "липсва Base URL" -> return activity.getString(R.string.server_test_missing_base_url)
            "липсва Access token" -> return activity.getString(R.string.server_test_missing_access_token)
            "липсва тестов телефон" -> return activity.getString(R.string.server_test_missing_phone)
            "неуспешен тест" -> return activity.getString(R.string.server_test_failed)
        }
        Regex("^HTTP (\\d+) · записът е потвърден · (.+)$").matchEntire(trimmed)?.let { match ->
            return activity.getString(R.string.server_test_record_confirmed, match.groupValues[1], match.groupValues[2])
        }
        return value
    }

    private fun testPhone(): String = binding.testsSection.phoneInput.text?.toString().orEmpty().trim().ifBlank { "0877904903" }
    private fun direction(): String = if (binding.testsSection.directionIn.isChecked) "in" else "out"
    private fun button(id: Int): MaterialButton = activity.findViewById(id)
}

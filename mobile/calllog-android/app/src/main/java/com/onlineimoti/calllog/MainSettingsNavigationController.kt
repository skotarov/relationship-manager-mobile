package com.onlineimoti.calllog

import android.net.Uri
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainSettingsNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val requestCreateServerSettingsArchive: (String) -> Unit,
    private val requestRestoreServerSettingsArchive: (String) -> Unit,
) {
    private var selectedSection: SettingsSection? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            showMenu()
        }
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    fun wire() {
        binding.settingsDetailBackButton.setOnClickListener { showMenu() }
        binding.settingsMenuGroup.settingsApplicationButton.setOnClickListener { showSection(SettingsSection.APPLICATION) }
        binding.settingsMenuGroup.settingsPopupButton.setOnClickListener { showSection(SettingsSection.POPUP) }
        binding.settingsMenuGroup.settingsCallLogButton.setOnClickListener { showSection(SettingsSection.CALL_LOG) }
        binding.settingsMenuGroup.settingsRmContactsButton.setOnClickListener { showSection(SettingsSection.INTEGRATION) }
        binding.settingsMenuGroup.settingsServerButton.setOnClickListener { showSection(SettingsSection.SERVER) }
        binding.settingsMenuGroup.settingsDataArchiveButton.setOnClickListener { showSection(SettingsSection.DATA_ARCHIVE) }
        if (BuildConfig.DEBUG) {
            binding.settingsMenuGroup.settingsDebugButton.setOnClickListener { showSection(SettingsSection.DEBUG) }
        } else {
            binding.settingsMenuGroup.settingsDebugButton.visibility = View.GONE
            binding.settingsDebugGroup.root.visibility = View.GONE
        }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener { saveServerSettingsArchive() }
        binding.remoteSettingsSection.restoreServerSettingsButton.setOnClickListener { restoreServerSettingsArchive() }
    }

    fun showMenu() {
        selectedSection = null
        backCallback.isEnabled = false
        binding.settingsMenuGroup.root.visibility = View.VISIBLE
        binding.settingsDescriptionText.visibility = View.VISIBLE
        binding.settingsDetailHeader.visibility = View.GONE
        binding.quickTestBar.visibility = View.GONE
        allGroupViews().forEach { it.visibility = View.GONE }
        if (!BuildConfig.DEBUG) binding.settingsDebugGroup.root.visibility = View.GONE
        scrollTop()
    }

    fun saveServerSettingsArchiveToUri(uri: Uri, code: String) {
        ServerSettingsArchiveFile.save(activity, uri, ConfigStore.load(activity), code)
            .onSuccess { fileName ->
                binding.remoteSettingsSection.serverBackupCodeInput.text?.clear()
                setStatus(activity.getString(R.string.server_settings_backup_saved, fileName))
            }
            .onFailure { error ->
                setStatus(activity.getString(R.string.server_settings_backup_failed, error.message.orEmpty()))
            }
    }

    fun restoreServerSettingsArchiveFromUri(uri: Uri, code: String) {
        when (val result = ServerSettingsArchiveFile.restore(activity, uri, ConfigStore.load(activity), code)) {
            is ServerSettingsBackupStore.RestoreResult.Restored -> {
                ConfigStore.save(activity, result.config)
                MainSettingsConfigUi.hydrateServerSettings(binding, result.config)
                binding.remoteSettingsSection.serverBackupCodeInput.text?.clear()
                setStatus(activity.getString(R.string.server_settings_backup_restored, fileName(uri)))
            }
            is ServerSettingsBackupStore.RestoreResult.Failed -> {
                setStatus(activity.getString(R.string.server_settings_backup_failed, result.message))
            }
        }
    }

    private fun saveServerSettingsArchive() {
        val code = archiveCode() ?: return
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(activity, config)
        requestCreateServerSettingsArchive(code)
    }

    private fun restoreServerSettingsArchive() {
        val code = archiveCode() ?: return
        requestRestoreServerSettingsArchive(code)
    }

    private fun archiveCode(): String? {
        val input = binding.remoteSettingsSection.serverBackupCodeInput
        val code = input.text?.toString().orEmpty()
        if (code.length == 4 && code.all(Char::isDigit)) return code
        input.error = activity.getString(R.string.settings_backup_code_required)
        return null
    }

    private fun fileName(uri: Uri): String {
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: ServerSettingsBackupStore.suggestedFileName()
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun showSection(section: SettingsSection) {
        if (section == SettingsSection.DEBUG && !BuildConfig.DEBUG) return
        selectedSection = section
        backCallback.isEnabled = true
        binding.settingsMenuGroup.root.visibility = View.GONE
        binding.settingsDescriptionText.visibility = View.GONE
        binding.settingsDetailHeader.visibility = View.VISIBLE
        binding.settingsDetailTitle.text = activity.getString(section.titleRes)
        allGroupViews().forEach { it.visibility = View.GONE }
        section.view(binding).visibility = View.VISIBLE
        binding.quickTestBar.visibility = if (section == SettingsSection.DEBUG && BuildConfig.DEBUG) View.VISIBLE else View.GONE
        scrollTop()
    }

    private fun allGroupViews(): List<View> = SettingsSection.values().map { it.view(binding) }

    private fun scrollTop() {
        binding.settingsScrollView.post { binding.settingsScrollView.scrollTo(0, 0) }
    }

    private enum class SettingsSection(val titleRes: Int) {
        APPLICATION(R.string.settings_application_section),
        POPUP(R.string.settings_popup_section),
        CALL_LOG(R.string.settings_call_log_section),
        INTEGRATION(R.string.settings_integration_section),
        SERVER(R.string.settings_server_section),
        DATA_ARCHIVE(R.string.settings_storage_section),
        DEBUG(R.string.settings_debug_section);

        fun view(binding: ActivityMainBinding): View {
            return when (this) {
                APPLICATION -> binding.settingsApplicationGroup.root
                POPUP -> binding.settingsPopupGroup.root
                CALL_LOG -> binding.settingsCallLogGroup.root
                INTEGRATION -> binding.settingsRmContactsGroup.root
                SERVER -> binding.settingsServerGroup.root
                DATA_ARCHIVE -> binding.settingsDataArchiveGroup.root
                DEBUG -> binding.settingsDebugGroup.root
            }
        }
    }
}

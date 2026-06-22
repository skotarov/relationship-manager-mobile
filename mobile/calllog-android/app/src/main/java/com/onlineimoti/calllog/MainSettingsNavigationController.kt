package com.onlineimoti.calllog

import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.onlineimoti.calllog.databinding.ActivityMainBinding

internal class MainSettingsNavigationController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
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
        binding.settingsMenuGroup.settingsRmContactsButton.setOnClickListener { showSection(SettingsSection.RM_CONTACTS) }
        binding.settingsMenuGroup.settingsServerButton.setOnClickListener { showSection(SettingsSection.SERVER) }
        binding.settingsMenuGroup.settingsDataArchiveButton.setOnClickListener { showSection(SettingsSection.DATA_ARCHIVE) }
        binding.settingsMenuGroup.settingsDebugButton.setOnClickListener { showSection(SettingsSection.DEBUG) }
        binding.remoteSettingsSection.saveServerSettingsButton.setOnClickListener {
            saveServerSettingsArchive()
        }
        binding.remoteSettingsSection.restoreServerSettingsButton.setOnClickListener {
            restoreServerSettingsArchive()
        }
    }

    fun showMenu() {
        selectedSection = null
        backCallback.isEnabled = false
        binding.settingsMenuGroup.root.visibility = View.VISIBLE
        binding.settingsDescriptionText.visibility = View.VISIBLE
        binding.settingsDetailHeader.visibility = View.GONE
        binding.quickTestBar.visibility = View.GONE
        allGroupViews().forEach { it.visibility = View.GONE }
        scrollTop()
    }

    private fun saveServerSettingsArchive() {
        val config = MainSettingsConfigUi.read(binding)
        ConfigStore.save(activity, config)
        PersistentServerSettingsArchive.save(activity, ConfigStore.load(activity))
            .onSuccess { path ->
                setStatus(activity.getString(R.string.server_settings_backup_saved, path))
            }
            .onFailure { error ->
                setStatus(activity.getString(R.string.server_settings_backup_failed, error.message.orEmpty()))
            }
    }

    private fun restoreServerSettingsArchive() {
        PersistentServerSettingsArchive.restore(activity, ConfigStore.load(activity))
            .onSuccess { config ->
                ConfigStore.save(activity, config)
                MainSettingsConfigUi.hydrateServerSettings(binding, config)
                setStatus(activity.getString(R.string.server_settings_backup_restored, PersistentServerSettingsArchive.path()))
            }
            .onFailure { error ->
                setStatus(activity.getString(R.string.server_settings_backup_failed, error.message.orEmpty()))
            }
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun showSection(section: SettingsSection) {
        selectedSection = section
        backCallback.isEnabled = true
        binding.settingsMenuGroup.root.visibility = View.GONE
        binding.settingsDescriptionText.visibility = View.GONE
        binding.settingsDetailHeader.visibility = View.VISIBLE
        binding.settingsDetailTitle.text = activity.getString(section.titleRes)
        allGroupViews().forEach { it.visibility = View.GONE }
        section.view(binding).visibility = View.VISIBLE
        binding.quickTestBar.visibility = if (section == SettingsSection.DEBUG) View.VISIBLE else View.GONE
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
        RM_CONTACTS(R.string.settings_crm_section),
        SERVER(R.string.settings_server_section),
        DATA_ARCHIVE(R.string.settings_storage_section),
        DEBUG(R.string.settings_debug_section);

        fun view(binding: ActivityMainBinding): View {
            return when (this) {
                APPLICATION -> binding.settingsApplicationGroup.root
                POPUP -> binding.settingsPopupGroup.root
                CALL_LOG -> binding.settingsCallLogGroup.root
                RM_CONTACTS -> binding.settingsRmContactsGroup.root
                SERVER -> binding.settingsServerGroup.root
                DATA_ARCHIVE -> binding.settingsDataArchiveGroup.root
                DEBUG -> binding.settingsDebugGroup.root
            }
        }
    }
}

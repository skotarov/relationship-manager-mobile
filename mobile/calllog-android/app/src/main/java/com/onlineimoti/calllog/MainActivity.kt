package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.role.RoleManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val text = if (granted) "Разрешението за notifications е дадено." else "Notifications остават забранени."
        setStatus(text)
    }

    private val phonePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val phoneStateGranted = result[Manifest.permission.READ_PHONE_STATE] == true
        val callLogGranted = result[Manifest.permission.READ_CALL_LOG] == true
        val contactsGranted = result[Manifest.permission.READ_CONTACTS] == true
        if (phoneStateGranted && callLogGranted && contactsGranted) {
            setStatus("Достъпът до телефон, call log и contacts е разрешен.")
        } else {
            setStatus("Без достъп до телефон, call log и contacts няма да работи коректно филтърът за познати номера.")
        }
    }

    private val callScreeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasCallScreeningRole()) {
            setStatus("Call screening role е активирана.")
        }
    }

    private val fullscreenIntentSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (canUseFullScreenIntent()) {
            setStatus("Разрешението за full-screen call popup е дадено.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CallReportRuntime.ensureNotificationChannel(this)
        requestNotificationPermissionIfNeeded()
        requestPhonePermissionsIfNeeded()
        requestCallScreeningRoleIfNeeded()
        requestFullScreenIntentPermissionIfNeeded()
        hydrateFields()

        binding.saveSettingsButton.setOnClickListener {
            saveConfig()
            setStatus("Настройките са записани локално.")
        }

        binding.openFormButton.setOnClickListener {
            saveConfig()
            openFormDirect()
        }

        binding.testNotificationButton.setOnClickListener {
            saveConfig()
            fetchLookupAndNotify()
        }
    }

    private fun hydrateFields() {
        val config = ConfigStore.load(this)
        binding.baseUrlInput.setText(config.baseUrl)
        binding.accessTokenInput.setText(config.accessToken)
        binding.contactGroupsInput.setText(config.contactGroups)
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
            contactGroups = binding.contactGroupsInput.text?.toString().orEmpty(),
        )
        ConfigStore.save(this, config)
        return ConfigStore.load(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestPhonePermissionsIfNeeded() {
        val missingPermissions = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_CALL_LOG)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_CONTACTS)
            }
        }
        if (missingPermissions.isEmpty()) {
            return
        }
        phonePermissionsLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun directionValue(): String {
        return if (binding.directionIn.isChecked) "in" else "out"
    }

    private fun phoneValue(): String {
        return binding.phoneInput.text?.toString()?.trim().orEmpty()
    }

    private fun openFormDirect() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }

        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/form.php",
            params = linkedMapOf(
                "phone" to phone,
                "direction" to directionValue(),
                "access_token" to config.accessToken,
            )
        )
        openWebView(url)
    }

    private fun fetchLookupAndNotify() {
        val config = ConfigStore.load(this)
        val phone = phoneValue()
        if (config.baseUrl.isBlank() || phone.isBlank()) {
            setStatus("Попълни Base URL и телефон.")
            return
        }

        binding.testNotificationButton.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        setStatus("Търся информация за $phone …")

        executor.execute {
            runCatching {
                CallReportRuntime.fetchLookup(config, phone, directionValue())
            }.onSuccess { result ->
                runOnUiThread {
                    binding.testNotificationButton.isEnabled = true
                    CallReportRuntime.showLookupNotification(this, result, fullscreen = true)
                    setStatus("Notification е обновен с lookup данните.")
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    binding.testNotificationButton.isEnabled = true
                    setStatus("Lookup грешка: ${throwable.message}")
                }
            }
        }
    }

    private fun openWebView(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url))
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun hasCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        if (hasCallScreeningRole()) {
            return
        }
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            return
        }
        callScreeningRoleLauncher.launch(
            roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
        )
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        return notificationManager.canUseFullScreenIntent()
    }

    private fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        if (canUseFullScreenIntent()) {
            return
        }

        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:$packageName")
        }
        fullscreenIntentSettingsLauncher.launch(intent)
    }
}

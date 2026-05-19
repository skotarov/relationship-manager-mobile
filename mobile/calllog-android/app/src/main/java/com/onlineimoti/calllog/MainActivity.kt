package com.onlineimoti.calllog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.onlineimoti.calllog.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
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
    }

    private fun saveConfig(): AppConfig {
        val config = AppConfig(
            baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
            accessToken = binding.accessTokenInput.text?.toString().orEmpty(),
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
                fetchLookup(config, phone, directionValue())
            }.onSuccess { result ->
                runOnUiThread {
                    binding.testNotificationButton.isEnabled = true
                    showLookupNotification(result)
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

    private fun fetchLookup(config: AppConfig, phone: String, direction: String): LookupResult {
        val url = buildEndpoint(
            baseUrl = config.baseUrl,
            path = "/broker/callreport/lookup.php",
            params = linkedMapOf(
                "phone" to phone,
                "direction" to direction,
                "access_token" to config.accessToken,
            )
        )

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        if (config.accessToken.isNotBlank()) {
            connection.setRequestProperty("X-Callreport-Token", config.accessToken)
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
        if (responseCode !in 200..299) {
            throw IllegalStateException("HTTP $responseCode: $body")
        }

        val json = JSONObject(body)
        val linesJson = json.optJSONArray("lines")
        val lines = buildList {
            if (linesJson != null) {
                for (index in 0 until linesJson.length()) {
                    add(linesJson.optString(index))
                }
            }
        }

        val openFormUrl = json.optString("open_form_url")
        val resolvedFormUrl = if (openFormUrl.startsWith("http")) {
            openFormUrl
        } else {
            config.baseUrl.trim().trimEnd('/') + openFormUrl
        }

        return LookupResult(
            title = json.optString("title", phone),
            subtitle = json.optString("subtitle", ""),
            lines = lines,
            openFormUrl = resolvedFormUrl,
        )
    }

    private fun showLookupNotification(result: LookupResult) {
        val openIntent = Intent(this, WebViewActivity::class.java)
            .putExtra(WebViewActivity.EXTRA_URL, result.openFormUrl)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(result.title)
            .setContentText(result.subtitle)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOf(result.subtitle)
                        .plus(result.lines.take(3))
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.open_form), pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(LOOKUP_NOTIFICATION_ID, notification)
    }

    private fun openWebView(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).putExtra(WebViewActivity.EXTRA_URL, url))
    }

    private fun setStatus(message: String) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "callreport_lookup"
        private const val LOOKUP_NOTIFICATION_ID = 2001
    }
}

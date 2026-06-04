package com.onlineimoti.calllog

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher

internal class MainPermissionFlowController(
    private val activity: MainActivity,
    private val requestPermissionLauncher: ActivityResultLauncher<String>,
    private val callScreeningRoleLauncher: ActivityResultLauncher<Intent>,
    private val storageSettingsLauncher: ActivityResultLauncher<Intent>,
    private val overlaySettingsLauncher: ActivityResultLauncher<Intent>,
    private val fullscreenIntentSettingsLauncher: ActivityResultLauncher<Intent>,
    private val hasPermission: (String) -> Boolean,
    private val canUsePublicNotesFolder: () -> Boolean,
    private val disablePublicNotesFolder: () -> Unit,
    private val disableOverlayPopups: () -> Unit,
    private val disableCallScreening: () -> Unit,
    private val refreshPermissionSummary: () -> Unit,
    private val setStatus: (String) -> Unit,
) {
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        requestNextStep()
    }

    fun requestPublicNotesStoragePermission() {
        isRunning = false
        if (!publicNotesFolderSelected()) return
        if (canUsePublicNotesFolder()) {
            migratePublicNotesIfPossible()
            return
        }
        setStatus("Разреши достъп до всички файлове, за да пазим бележките в публичната папка ${LocalNotesFileStore.publicRootPath()}.")
        requestStorageManagerPermissionIfNeeded()
    }

    fun onPermissionResult() {
        refreshPermissionSummary()
        requestNextStep()
    }

    fun onCallScreeningResult() {
        if (hasCallScreeningRole()) {
            setStatus("Call screening role е активирана.")
            refreshPermissionSummary()
            requestNextStep()
            return
        }
        if (callScreeningSelected()) {
            disableCallScreening()
            setStatus("Call screening / Caller ID ролята не е активирана. Настройката е изключена и приложението няма да я иска отново автоматично.")
        }
        refreshPermissionSummary()
        isRunning = false
    }

    fun onStorageSettingsResult() {
        if (canUsePublicNotesFolder()) {
            migratePublicNotesIfPossible()
            refreshPermissionSummary()
            requestNextStep()
            return
        }
        if (publicNotesFolderSelected()) {
            disablePublicNotesFolder()
            setStatus("Достъпът до общата файлова система не е разрешен. Настройката е изключена и бележките ще се пазят във вътрешната памет на приложението.")
        }
        refreshPermissionSummary()
        isRunning = false
    }

    fun onOverlaySettingsResult() {
        if (Settings.canDrawOverlays(activity)) {
            setStatus("Разрешението Display over other apps е дадено.")
        } else if (overlayPopupsSelected()) {
            disableOverlayPopups()
            setStatus("Overlay разрешението не е дадено. Настройката е изключена и приложението ще използва системен popup.")
        }
        refreshPermissionSummary()
        isRunning = false
    }

    fun onFullscreenIntentSettingsResult() {
        if (canUseFullScreenIntent()) setStatus("Разрешението за full-screen call report popup е дадено.")
        refreshPermissionSummary()
        isRunning = false
    }

    fun requestNextStep() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) -> {
                setStatus("Разреши notifications, за да могат системните popup-и да се показват.")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !hasPermission(Manifest.permission.READ_PHONE_STATE) -> {
                setStatus("Разреши Phone, за да засичаме начало и край на разговор.")
                requestPermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
            !hasPermission(Manifest.permission.READ_CALL_LOG) -> {
                setStatus("Разреши Call log, за да виждаме последните разговори.")
                requestPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            }
            !hasPermission(Manifest.permission.READ_CONTACTS) -> {
                setStatus("Разреши Contacts, за да показваме имена вместо само номера.")
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
            !hasPermission(Manifest.permission.WRITE_CONTACTS) -> {
                setStatus("Разреши Contacts write за съвместимост със старите бележки към контакти.")
                requestPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            }
            publicNotesFolderSelected() && !canUsePublicNotesFolder() -> {
                setStatus("Разреши достъп до всички файлове, за да пазим бележките в публичната папка ${LocalNotesFileStore.publicRootPath()}.")
                requestStorageManagerPermissionIfNeeded()
            }
            callScreeningSelected() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasCallScreeningRole() -> {
                setStatus("Call screening е опционално. Активирай го, ако popup-ът не се показва навреме при входящ разговор.")
                requestCallScreeningRoleIfNeeded()
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !canUseFullScreenIntent() -> {
                setStatus("Разреши Full-screen popup от системния екран.")
                requestFullScreenIntentPermissionIfNeeded()
            }
            else -> finishFlowWithSuccess()
        }
    }

    fun requestCallScreeningRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasCallScreeningRole()) {
            finishFlowWithoutStatus()
            return
        }
        val roleManager = activity.getSystemService(RoleManager::class.java) ?: run {
            disableUnavailableCallScreeningIfSelected()
            return
        }
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            disableUnavailableCallScreeningIfSelected()
            return
        }
        callScreeningRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    fun requestFullScreenIntentPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent()) {
            finishFlowWithoutStatus()
            return
        }
        fullscreenIntentSettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply { data = Uri.parse("package:${activity.packageName}") }
        )
    }

    fun requestOverlayPermissionIfNeeded() {
        if (Settings.canDrawOverlays(activity)) {
            setStatus("Display over other apps вече е разрешено.")
            refreshPermissionSummary()
            return
        }
        overlaySettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") }
        )
    }

    private fun requestStorageManagerPermissionIfNeeded() {
        if (!publicNotesFolderSelected() || canUsePublicNotesFolder()) {
            requestNextStep()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storageSettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${activity.packageName}") }
            )
        } else {
            requestNextStep()
        }
    }

    private fun migratePublicNotesIfPossible() {
        if (!publicNotesFolderSelected()) return
        if (!canUsePublicNotesFolder()) return
        val migrated = LocalNotesFileStore.migratePrivateToPublic(activity)
        if (migrated) {
            setStatus("Публичната папка за бележки е активна: ${LocalNotesFileStore.publicRootPath()}")
        } else {
            setStatus("Публичната папка е разрешена, но прехвърлянето на бележките не успя.")
        }
    }

    private fun disableUnavailableCallScreeningIfSelected() {
        if (callScreeningSelected()) {
            disableCallScreening()
            setStatus("Call screening / Caller ID ролята не е налична на този телефон. Настройката е изключена.")
        }
        isRunning = false
        refreshPermissionSummary()
    }

    private fun publicNotesFolderSelected(): Boolean = ConfigStore.load(activity).usePublicNotesFolder
    private fun overlayPopupsSelected(): Boolean = ConfigStore.load(activity).useOverlayPopups
    private fun callScreeningSelected(): Boolean = ConfigStore.load(activity).useCallScreening
    private fun hasCallScreeningRole(): Boolean = MainPermissionChecks.hasCallScreeningRole(activity)
    private fun canUseFullScreenIntent(): Boolean = MainPermissionChecks.canUseFullScreenIntent(activity)

    private fun finishFlowWithSuccess() {
        isRunning = false
        if (publicNotesFolderSelected() && canUsePublicNotesFolder()) migratePublicNotesIfPossible()
        setStatus("Основните разрешения са проверени. Бележките са в ${LocalNotesFileStore.activeRootPath(activity)}.")
        refreshPermissionSummary()
    }

    private fun finishFlowWithoutStatus() {
        isRunning = false
        refreshPermissionSummary()
    }
}

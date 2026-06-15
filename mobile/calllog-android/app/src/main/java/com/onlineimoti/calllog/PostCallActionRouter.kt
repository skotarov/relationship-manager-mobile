package com.onlineimoti.calllog

import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object PostCallActionRouter {
    fun route(
        context: Context,
        phone: String,
        direction: String,
        title: String,
        formUrl: String = "",
        config: AppConfig = ConfigStore.load(context),
    ) {
        when (config.postCallEndAction) {
            ConfigStore.POST_CALL_END_ACTION_NOTHING -> return
            ConfigStore.POST_CALL_END_ACTION_HISTORY -> {
                if (shouldUseOverlay(context, config)) {
                    startOverlay(context, formUrl, phone, direction, title)
                } else {
                    CallNoteEditorLauncher.startHistory(context, phone, title.ifBlank { phone.ifBlank { "История" } })
                }
            }
            else -> {
                if (shouldUseOverlay(context, config)) {
                    startOverlay(context, formUrl, phone, direction, title)
                } else {
                    CallNoteEditorLauncher.startEditor(
                        context = context,
                        mode = PostCallOverlayService.MODE_NOTE,
                        phone = phone,
                        title = title.ifBlank { phone.ifBlank { "Бележка от разговора" } },
                        direction = direction,
                        actionIssuedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private fun shouldUseOverlay(context: Context, config: AppConfig): Boolean {
        return config.useOverlayPopups && config.useCustomEndPopup && Settings.canDrawOverlays(context)
    }

    private fun startOverlay(context: Context, formUrl: String, phone: String, direction: String, title: String) {
        context.startService(
            Intent(context, PostCallOverlayService::class.java)
                .putExtra(PostCallOverlayService.EXTRA_MODE, PostCallOverlayService.MODE_CALL_ENDED)
                .putExtra(PostCallOverlayService.EXTRA_FORM_URL, formUrl)
                .putExtra(PostCallOverlayService.EXTRA_PHONE, phone)
                .putExtra(PostCallOverlayService.EXTRA_DIRECTION, direction)
                .putExtra(PostCallOverlayService.EXTRA_TITLE, title)
                .putExtra(PostCallOverlayService.EXTRA_SUBTITLE, if (formUrl.isBlank()) "Локален режим — без сървърна бележка" else "")
                .putExtra(CallNoteTargetResolver.EXTRA_ACTION_ISSUED_AT, System.currentTimeMillis())
        )
    }
}

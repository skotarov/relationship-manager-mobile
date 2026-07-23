package com.onlineimoti.calllog

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class ContactNotesHeaderUi(
    private val activity: Activity,
    private val dp: (Int) -> Int,
) {
    private val actions by lazy { ContactNotesHeaderActionsUi(activity, dp) }
    private val identityUi by lazy { ContactNotesIdentityUi(activity, dp) }

    fun headerRow(
        title: String,
        phone: String,
        contactExists: Boolean,
        showRmCallLogButton: Boolean,
        showCrmSyncButton: Boolean,
        crmSyncEnabled: Boolean,
        crmSyncBusy: Boolean,
        crmSyncServerBacked: Boolean,
        goBack: () -> Unit,
        openDialer: () -> Unit,
        openCalendarEvent: () -> Unit,
        openDefaultContact: () -> Unit,
        openRmContact: () -> Unit,
        toggleCrmSync: () -> Unit,
        openRmCallLog: () -> Unit,
        openRmCallLogFiltered: () -> Unit,
    ): LinearLayout {
        val displayName = identityUi.displayNameFromTitle(title, phone)
        val contactDescription = activity.getString(
            if (contactExists) R.string.dynamic_contact_open else R.string.dynamic_contact_create,
        )
        val compactTitle = identityUi.compactTitle(displayName.ifBlank { phone })
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
            addView(actions.backButton(
                goBack = goBack,
                openCleanCallList = if (showRmCallLogButton) openRmCallLog else null,
            ).apply { layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)) })
            addView(compactTitle)
        }
        val actionRow = actionRow(
            phone = phone,
            title = title,
            displayName = displayName,
            contactExists = contactExists,
            contactDescription = contactDescription,
            crmSyncAvailable = showCrmSyncButton,
            crmSyncEnabled = crmSyncEnabled,
            crmSyncBusy = crmSyncBusy,
            crmSyncServerBacked = crmSyncServerBacked,
            openDialer = openDialer,
            openCalendarEvent = openCalendarEvent,
            openDefaultContact = openDefaultContact,
            openRmContact = openRmContact,
            toggleCrmSync = toggleCrmSync,
        ).apply { setBackgroundColor(activity.getColor(R.color.calllog_bg)) }
        val actionAnchor = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ACTION_ANCHOR_HEIGHT_DP),
            )
            addView(actionRow, actionRowHostLayoutParams())
            tag = ContactNotesStickyActions(actionRow, topBar, compactTitle)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(topBar)
            addView(identityUi.block(displayName, phone, contactExists))
            addView(actionAnchor)
        }
    }

    fun sectionTitleWithDrawable(textValue: String, drawableRes: Int): LinearLayout =
        titleRow(textValue).apply {
            addView(ImageView(activity).apply {
                setImageResource(drawableRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) }
            }, 0)
        }

    fun directionArrowLabel(direction: String): String = when (direction) {
        "in" -> activity.getString(R.string.dynamic_direction_in)
        "out" -> activity.getString(R.string.dynamic_direction_out)
        else -> PhoneCallReader.directionLabel(direction)
    }

    private fun actionRow(
        phone: String,
        title: String,
        displayName: String,
        contactExists: Boolean,
        contactDescription: String,
        crmSyncAvailable: Boolean,
        crmSyncEnabled: Boolean,
        crmSyncBusy: Boolean,
        crmSyncServerBacked: Boolean,
        openDialer: () -> Unit,
        openCalendarEvent: () -> Unit,
        openDefaultContact: () -> Unit,
        openRmContact: () -> Unit,
        toggleCrmSync: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ACTION_ROW_HEIGHT_DP),
            )
        }
        ContactNotesHeaderActionPolicy.ordered(contactExists).forEachIndexed { index, kind ->
            val button = when (kind) {
                ContactNotesHeaderAction.CRM -> actions.crmSyncButton(
                    enabled = crmSyncEnabled,
                    busy = crmSyncBusy,
                    serverBacked = crmSyncServerBacked,
                    available = crmSyncAvailable,
                    action = toggleCrmSync,
                )
                ContactNotesHeaderAction.CALENDAR -> actions.iconButton(
                    R.drawable.ic_calendar_event,
                    activity.getString(R.string.dynamic_action_calendar),
                    openCalendarEvent,
                )
                ContactNotesHeaderAction.CONTACT -> actions.contactMenuButton(
                    contactDescription,
                    openDefaultContact,
                    openRmContact,
                )
                ContactNotesHeaderAction.ADD_CONTACT -> actions.iconButton(
                    R.drawable.ic_contact_person_add,
                    contactDescription,
                    openDefaultContact,
                )
                ContactNotesHeaderAction.CALL -> actions.iconButton(
                    R.drawable.ic_phone_call,
                    activity.getString(R.string.dynamic_action_call),
                    openDialer,
                )
                ContactNotesHeaderAction.SMS -> actions.iconButton(
                    R.drawable.ic_sms_message,
                    activity.getString(R.string.dynamic_action_write_sms),
                ) {
                    SmsComposeAction.open(
                        activity = activity,
                        phone = phone,
                        title = displayName.ifBlank { title },
                        dp = dp,
                    )
                }
            }
            row.addView(actionSlot(button, insetStart = index == 0))
        }
        return row
    }

    private fun actionRowHostLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(ACTION_ROW_HEIGHT_DP),
        Gravity.BOTTOM,
    )

    private fun actionSlot(button: View, insetStart: Boolean): LinearLayout {
        button.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            if (insetStart) setPadding(dp(CRM_SLOT_START_PADDING_DP), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            addView(button)
        }
    }

    private fun titleRow(textValue: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(8))
        addView(TextView(activity).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(30, 41, 59))
        })
    }

    private companion object {
        const val CRM_SLOT_START_PADDING_DP = 6
        const val ACTION_ANCHOR_HEIGHT_DP = 50
        const val ACTION_ROW_HEIGHT_DP = 48
    }
}

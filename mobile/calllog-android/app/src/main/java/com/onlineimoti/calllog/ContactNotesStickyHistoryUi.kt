package com.onlineimoti.calllog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

internal object ContactNotesStickyActionPolicy {
    /** Pin the action row exactly when its reserved place reaches the viewport top. */
    fun shouldStick(actionTopOnScreen: Int, viewportTopOnScreen: Int): Boolean =
        actionTopOnScreen <= viewportTopOnScreen

    /** The compact identity appears at the same moment as the action row becomes pinned. */
    fun shouldShowCompactIdentity(actionsPinned: Boolean): Boolean = actionsPinned
}

/** Builds History with fixed top identity/actions and a fixed bottom list-mode switch. */
internal class ContactNotesStickyHistoryUi(
    private val activity: ContactNotesActivity,
) {
    private var stickyController: StickyGroupHeaderController? = null
    private var scrollView: ScrollView? = null
    private var scrollChangedListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var layoutChangeListener: View.OnLayoutChangeListener? = null
    private var savedScrollY = 0
    private val screenLocation = IntArray(2)

    fun resetScrollPosition() {
        savedScrollY = 0
        scrollView?.scrollTo(0, 0)
    }

    fun show(
        root: LinearLayout,
        refreshing: Boolean,
        onRefresh: () -> Unit,
        bindPaging: (ScrollView, LinearLayout) -> Unit,
        mode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ) {
        scrollView?.let { savedScrollY = it.scrollY }
        release()
        val actionAnchor = findActionAnchor(root) as? ViewGroup
        val stickyHeader = actionAnchor?.tag as? ContactNotesStickyActions
        val topBar = stickyHeader?.topBar
        val actionRow = stickyHeader?.actionRow
        (topBar?.parent as? ViewGroup)?.removeView(topBar)

        val historyScroll = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            addView(root)
        }
        scrollView = historyScroll

        val groupOverlay = TextView(activity)
        val groupOverlayParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        )
        val stickyActionHost = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            elevation = 0f
            stateListAnimator = null
        }
        val stickyActionHostParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(STICKY_ACTION_HEIGHT_DP),
            Gravity.TOP,
        )

        val viewport = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(historyScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(groupOverlay, groupOverlayParams)
            addView(stickyActionHost, stickyActionHostParams)
        }
        val screen = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            topBar?.let { bar ->
                addView(bar, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(FIXED_TOP_BAR_HEIGHT_DP),
                ))
            }
            addView(viewport, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(historyModeBar(mode, onModeSelected), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MODE_BAR_HEIGHT_DP),
            ))
        }

        fun updateStickyUi() {
            if (!historyScroll.isAttachedToWindow || historyScroll.height <= 0) return
            savedScrollY = historyScroll.scrollY
            val anchor = actionAnchor
            val row = actionRow
            val actionsPinned = if (
                anchor != null && row != null && anchor.isAttachedToWindow && anchor.height > 0
            ) {
                ContactNotesStickyActionPolicy.shouldStick(
                    actionTopOnScreen = topOnScreen(anchor),
                    viewportTopOnScreen = topOnScreen(historyScroll),
                )
            } else {
                false
            }

            if (anchor != null && row != null) {
                if (actionsPinned) {
                    moveActionRow(row, stickyActionHost)
                    stickyActionHost.visibility = View.VISIBLE
                    row.elevation = 0f
                    stickyActionHost.bringToFront()
                } else {
                    moveActionRow(row, anchor)
                    row.elevation = 0f
                    stickyActionHost.visibility = View.INVISIBLE
                }
            }

            stickyHeader?.compactTitle?.let { compactTitle ->
                val showCompact = compactTitle.text.isNotBlank() &&
                    ContactNotesStickyActionPolicy.shouldShowCompactIdentity(actionsPinned)
                val titleVisibility = if (showCompact) View.VISIBLE else View.INVISIBLE
                if (compactTitle.visibility != titleVisibility) compactTitle.visibility = titleVisibility
            }

            val groupTop = if (actionsPinned) dp(STICKY_ACTION_HEIGHT_DP) else 0
            if (groupOverlayParams.topMargin != groupTop) {
                groupOverlayParams.topMargin = groupTop
                groupOverlay.layoutParams = groupOverlayParams
            }
        }

        scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
            updateStickyUi()
        }.also { listener ->
            historyScroll.viewTreeObserver.addOnScrollChangedListener(listener)
        }
        layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateStickyUi()
        }.also(historyScroll::addOnLayoutChangeListener)
        historyScroll.post {
            val maxScrollY = maxOf(0, root.height - historyScroll.height)
            historyScroll.scrollTo(0, savedScrollY.coerceIn(0, maxScrollY))
            updateStickyUi()
        }
        bindPaging(historyScroll, root)
        activity.setContentView(PullToRefreshLayout(activity).apply {
            addView(screen)
            setOnRefreshListener(onRefresh)
            if (refreshing) setRefreshing(true)
        })
        stickyController = StickyGroupHeaderController(historyScroll, root, groupOverlay).also { it.bind() }
    }

    fun release() {
        val currentScroll = scrollView
        currentScroll?.let { savedScrollY = it.scrollY }
        scrollChangedListener?.let { listener ->
            val observer = currentScroll?.viewTreeObserver
            if (observer?.isAlive == true) observer.removeOnScrollChangedListener(listener)
        }
        scrollChangedListener = null
        layoutChangeListener?.let { listener -> currentScroll?.removeOnLayoutChangeListener(listener) }
        layoutChangeListener = null
        scrollView = null
        stickyController?.release()
        stickyController = null
    }

    private fun historyModeBar(
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
        setPadding(dp(PAGE_HORIZONTAL_PADDING_DP), 0, dp(PAGE_HORIZONTAL_PADDING_DP), 0)
        elevation = dp(8).toFloat()
        addView(modeButton(
            textValue = "Бележки и SMS",
            mode = ContactHistoryListMode.NOTES_AND_SMS,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
        addView(modeButton(
            textValue = "Пълен лог",
            mode = ContactHistoryListMode.FULL_LOG,
            selectedMode = selectedMode,
            onModeSelected = onModeSelected,
        ))
    }

    private fun modeButton(
        textValue: String,
        mode: ContactHistoryListMode,
        selectedMode: ContactHistoryListMode,
        onModeSelected: (ContactHistoryListMode) -> Unit,
    ): TextView {
        val selected = mode == selectedMode
        return TextView(activity).apply {
            text = textValue
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else Color.rgb(51, 65, 85))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (selected) activity.getColor(R.color.callreport_icon_background) else Color.TRANSPARENT)
                setStroke(dp(1), activity.getColor(R.color.calllog_border))
            }
            isClickable = !selected
            isFocusable = !selected
            contentDescription = textValue
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            )
            setOnClickListener { if (!selected) onModeSelected(mode) }
        }
    }

    private fun moveActionRow(row: LinearLayout, target: ViewGroup) {
        if (row.parent === target) return
        (row.parent as? ViewGroup)?.removeView(row)
        target.removeAllViews()
        target.addView(row, actionRowLayoutParams())
    }

    private fun actionRowLayoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(ACTION_ROW_HEIGHT_DP),
        Gravity.BOTTOM,
    )

    private fun topOnScreen(view: View): Int {
        view.getLocationOnScreen(screenLocation)
        return screenLocation[1]
    }

    private fun findActionAnchor(view: View): View? {
        if (view.tag is ContactNotesStickyActions) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findActionAnchor(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val PAGE_HORIZONTAL_PADDING_DP = 16
        const val FIXED_TOP_BAR_HEIGHT_DP = 50
        const val STICKY_ACTION_HEIGHT_DP = 50
        const val ACTION_ROW_HEIGHT_DP = 48
        const val MODE_BAR_HEIGHT_DP = 48
    }
}

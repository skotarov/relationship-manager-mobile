package com.onlineimoti.calllog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

/** Builds History with one real action row that moves into and out of a fixed host. */
internal class ContactNotesStickyHistoryUi(
    private val activity: ContactNotesActivity,
) {
    private var stickyController: StickyGroupHeaderController? = null
    private var scrollView: ScrollView? = null
    private var layoutChangeListener: View.OnLayoutChangeListener? = null
    private val screenLocation = IntArray(2)

    fun show(
        root: LinearLayout,
        refreshing: Boolean,
        onRefresh: () -> Unit,
        bindPaging: (ScrollView, LinearLayout) -> Unit,
    ) {
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
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        }
        val stickyActionHost = FrameLayout(activity).apply {
            visibility = View.INVISIBLE
            setBackgroundColor(ContextCompat.getColor(activity, R.color.calllog_bg))
            elevation = dp(8).toFloat()
        }
        val stickyActionHostParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(STICKY_ACTION_HEIGHT_DP),
            Gravity.TOP,
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
        }

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
        }

        fun updateStickyUi() {
            if (!historyScroll.isAttachedToWindow || historyScroll.height <= 0) return
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
                    row.elevation = dp(8).toFloat()
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

        historyScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyUi() }
        layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateStickyUi()
        }.also(historyScroll::addOnLayoutChangeListener)
        historyScroll.post { updateStickyUi() }
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
        layoutChangeListener?.let { listener -> currentScroll?.removeOnLayoutChangeListener(listener) }
        layoutChangeListener = null
        currentScroll?.setOnScrollChangeListener(null)
        scrollView = null
        stickyController?.release()
        stickyController = null
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
        const val FIXED_TOP_BAR_HEIGHT_DP = 50
        const val STICKY_ACTION_HEIGHT_DP = 50
        const val ACTION_ROW_HEIGHT_DP = 48
    }
}

package com.onlineimoti.calllog

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

internal object ContactNotesStickyActionPolicy {
    fun shouldStick(scrollY: Int, anchorTop: Int): Boolean =
        anchorTop >= 0 && scrollY >= anchorTop

    fun shouldShowCompactIdentity(scrollY: Int, identityBottom: Int): Boolean =
        identityBottom >= 0 && scrollY >= identityBottom
}

/** Builds History with a fixed back bar, sticky actions and a group title overlay. */
internal class ContactNotesStickyHistoryUi(
    private val activity: ContactNotesActivity,
) {
    private var stickyController: StickyGroupHeaderController? = null
    private var scrollView: ScrollView? = null

    fun show(
        root: LinearLayout,
        refreshing: Boolean,
        onRefresh: () -> Unit,
        bindPaging: (ScrollView, LinearLayout) -> Unit,
    ) {
        release()
        val actionAnchor = findActionAnchor(root)
        val stickyHeader = actionAnchor?.tag as? ContactNotesStickyActions
        val topBar = stickyHeader?.topBar
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
        val stickyActionBar = stickyHeader?.overlay
        val viewport = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(historyScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(groupOverlay, groupOverlayParams)
            stickyActionBar?.let { actionBar ->
                actionBar.visibility = View.INVISIBLE
                addView(actionBar, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(STICKY_ACTION_HEIGHT_DP),
                    Gravity.TOP,
                ).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                })
                actionBar.bringToFront()
            }
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
            if (!historyScroll.isAttachedToWindow) return
            val scrollY = historyScroll.scrollY
            val identityAnchor = stickyHeader?.identityAnchor
            val compactTitle = stickyHeader?.compactTitle
            if (identityAnchor != null && compactTitle != null && identityAnchor.isAttachedToWindow) {
                val identityBottom = viewBoundsInRoot(root, identityAnchor).bottom
                val showCompact = compactTitle.text.isNotBlank() &&
                    ContactNotesStickyActionPolicy.shouldShowCompactIdentity(scrollY, identityBottom)
                val titleVisibility = if (showCompact) View.VISIBLE else View.INVISIBLE
                if (compactTitle.visibility != titleVisibility) compactTitle.visibility = titleVisibility
            }

            val anchor = actionAnchor
            val actionBar = stickyActionBar
            var actionsPinned = false
            if (anchor != null && actionBar != null && anchor.isAttachedToWindow) {
                actionsPinned = ContactNotesStickyActionPolicy.shouldStick(
                    scrollY = scrollY,
                    anchorTop = viewBoundsInRoot(root, anchor).top,
                )
                val actionVisibility = if (actionsPinned) View.VISIBLE else View.INVISIBLE
                if (actionBar.visibility != actionVisibility) actionBar.visibility = actionVisibility
                if (actionsPinned) actionBar.bringToFront()
            }
            val groupTop = if (actionsPinned) dp(STICKY_ACTION_HEIGHT_DP) else 0
            if (groupOverlayParams.topMargin != groupTop) {
                groupOverlayParams.topMargin = groupTop
                groupOverlay.layoutParams = groupOverlayParams
            }
        }

        historyScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyUi() }
        historyScroll.post { updateStickyUi() }
        stickyActionBar?.post { updateStickyUi() }
        bindPaging(historyScroll, root)
        activity.setContentView(PullToRefreshLayout(activity).apply {
            addView(screen)
            setOnRefreshListener(onRefresh)
            if (refreshing) setRefreshing(true)
        })
        stickyController = StickyGroupHeaderController(historyScroll, root, groupOverlay).also { it.bind() }
    }

    fun release() {
        scrollView?.setOnScrollChangeListener(null)
        scrollView = null
        stickyController?.release()
        stickyController = null
    }

    private fun viewBoundsInRoot(root: ViewGroup, view: View): Rect {
        val rect = Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        root.offsetDescendantRectToMyCoords(view, rect)
        return rect
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
    }
}

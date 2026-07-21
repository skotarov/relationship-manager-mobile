package com.onlineimoti.calllog

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Builds History with sticky actions and a group title overlay without changing content height. */
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
        val historyScroll = ScrollView(activity).apply {
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
        val actionAnchor = findActionAnchor(root)
        val stickyActionBar = (actionAnchor?.tag as? ContactNotesStickyActions)?.overlay
        val viewport = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(historyScroll, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(groupOverlay, groupOverlayParams)
            stickyActionBar?.let { actionBar ->
                actionBar.visibility = View.INVISIBLE
                addView(actionBar, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ).apply {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                })
            }
        }

        fun updateStickyActions() {
            val anchor = actionAnchor ?: return
            val actionBar = stickyActionBar ?: return
            if (!anchor.isAttachedToWindow || !historyScroll.isAttachedToWindow) return
            val anchorLocation = IntArray(2)
            val viewportLocation = IntArray(2)
            anchor.getLocationOnScreen(anchorLocation)
            historyScroll.getLocationOnScreen(viewportLocation)
            val shouldStick = anchorLocation[1] <= viewportLocation[1]
            val nextVisibility = if (shouldStick) View.VISIBLE else View.INVISIBLE
            if (actionBar.visibility != nextVisibility) actionBar.visibility = nextVisibility
            val topMargin = if (shouldStick) actionBar.height else 0
            if (groupOverlayParams.topMargin != topMargin) {
                groupOverlayParams.topMargin = topMargin
                groupOverlay.layoutParams = groupOverlayParams
            }
        }

        historyScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateStickyActions() }
        historyScroll.post { updateStickyActions() }
        bindPaging(historyScroll, root)
        activity.setContentView(PullToRefreshLayout(activity).apply {
            addView(viewport)
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
}

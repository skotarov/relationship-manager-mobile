package com.onlineimoti.calllog

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.max
import kotlin.math.min

/**
 * Pull-to-refresh container for the app's existing ScrollViews.
 *
 * It starts only while the child is already at the top. The spinner follows the
 * drag, disappears when the gesture is cancelled, and calls [setOnRefreshListener]
 * only when the finger is released after the trigger distance.
 */
internal class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val triggerDistance = dp(88).toFloat()
    private val maximumPullDistance = dp(144).toFloat()
    private val restingSpinnerOffset = dp(10).toFloat()
    private val spinnerSize = dp(36)

    private var contentView: View? = null
    private var initialTouchY = 0f
    private var pullDistance = 0f
    private var dragging = false
    private var refreshListener: (() -> Unit)? = null

    private val spinner = ProgressBar(context).apply {
        alpha = 0f
        scaleX = IDLE_SPINNER_SCALE
        scaleY = IDLE_SPINNER_SCALE
        visibility = INVISIBLE
        layoutParams = LayoutParams(
            spinnerSize,
            spinnerSize,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(6)
        }
    }

    var isRefreshing: Boolean = false
        private set

    init {
        clipChildren = false
        clipToPadding = false
        addView(spinner)
    }

    fun setOnRefreshListener(listener: (() -> Unit)?) {
        refreshListener = listener
    }

    fun setRefreshing(refreshing: Boolean) {
        if (refreshing) {
            if (!isRefreshing) startRefreshing()
        } else {
            stopRefreshing()
        }
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        if (child === spinner) return
        contentView = child
        bringChildToFront(spinner)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val content = contentView ?: return super.onInterceptTouchEvent(event)
        if (isRefreshing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchY = event.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val distance = event.y - initialTouchY
                if (distance > touchSlop && !canScrollUp(content)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val content = contentView ?: return super.onTouchEvent(event)
        if (isRefreshing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchY = event.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawDistance = event.y - initialTouchY
                if (!dragging) {
                    if (rawDistance <= touchSlop || canScrollUp(content)) return false
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                updatePull(max(0f, rawDistance - touchSlop))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return false
                dragging = false
                if (pullDistance >= triggerDistance) {
                    startRefreshing()
                    refreshListener?.invoke() ?: stopRefreshing()
                } else {
                    cancelPull()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) cancelPull()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updatePull(rawDistance: Float) {
        pullDistance = min(rawDistance * DRAG_RESISTANCE, maximumPullDistance)
        contentView?.translationY = pullDistance
        if (pullDistance <= 0f) {
            spinner.visibility = INVISIBLE
            spinner.alpha = 0f
            spinner.translationY = 0f
            spinner.scaleX = IDLE_SPINNER_SCALE
            spinner.scaleY = IDLE_SPINNER_SCALE
            return
        }
        val progress = (pullDistance / triggerDistance).coerceIn(0f, 1f)
        spinner.visibility = VISIBLE
        spinner.translationY = max(0f, pullDistance - spinnerSize)
        spinner.alpha = MIN_SPINNER_ALPHA + (1f - MIN_SPINNER_ALPHA) * progress
        val scale = IDLE_SPINNER_SCALE + (1f - IDLE_SPINNER_SCALE) * progress
        spinner.scaleX = scale
        spinner.scaleY = scale
    }

    private fun startRefreshing() {
        isRefreshing = true
        pullDistance = triggerDistance
        spinner.visibility = VISIBLE
        contentView?.animate()
            ?.translationY(0f)
            ?.setDuration(ANIMATION_DURATION_MS)
            ?.start()
        spinner.animate()
            .translationY(restingSpinnerOffset)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ANIMATION_DURATION_MS)
            .start()
    }

    private fun stopRefreshing() {
        isRefreshing = false
        cancelPull()
    }

    private fun cancelPull() {
        pullDistance = 0f
        contentView?.animate()
            ?.translationY(0f)
            ?.setDuration(ANIMATION_DURATION_MS)
            ?.start()
        spinner.animate()
            .translationY(0f)
            .alpha(0f)
            .scaleX(IDLE_SPINNER_SCALE)
            .scaleY(IDLE_SPINNER_SCALE)
            .setDuration(ANIMATION_DURATION_MS)
            .withEndAction {
                if (!isRefreshing) spinner.visibility = INVISIBLE
            }
            .start()
    }

    private fun canScrollUp(view: View): Boolean {
        if (view.canScrollVertically(-1)) return true
        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (canScrollUp(group.getChildAt(index))) return true
        }
        return false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DRAG_RESISTANCE = 0.52f
        const val MIN_SPINNER_ALPHA = 0.35f
        const val IDLE_SPINNER_SCALE = 0.62f
        const val ANIMATION_DURATION_MS = 180L
    }
}

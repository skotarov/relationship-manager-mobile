package com.onlineimoti.calllog

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/** Marks group titles and keeps the active title fixed over a scrolling list. */
internal object StickyGroupHeaderUi {
    private val marker = Any()

    fun mark(view: TextView): TextView = view.apply { tag = marker }

    fun prepareOverlay(view: TextView): TextView = view.apply {
        visibility = View.GONE
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(ContextCompat.getColor(context, R.color.callreport_icon_background))
        setBackgroundColor(ContextCompat.getColor(context, R.color.calllog_bg))
        val density = resources.displayMetrics.density
        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        elevation = 4 * density
    }

    fun isMarked(view: View): Boolean {
        if (view.tag === marker) return true
        val text = view as? TextView ?: return false
        val sizeSp = text.textSize / text.resources.displayMetrics.scaledDensity
        return text.text.isNotBlank() && text.background == null && text.typeface?.isBold == true &&
            abs(sizeSp - 12.5f) < 0.2f &&
            text.currentTextColor == ContextCompat.getColor(text.context, R.color.callreport_icon_background)
    }
}

internal data class StickyGroupHeaderState(
    val activeIndex: Int,
    val translationY: Int,
)

internal object StickyGroupHeaderPolicy {
    fun resolve(headerTops: List<Int>, overlayHeight: Int): StickyGroupHeaderState? {
        val activeIndex = headerTops.indexOfLast { it < 0 }
        if (activeIndex < 0) return null
        val nextTop = headerTops.getOrNull(activeIndex + 1)
        val translation = nextTop?.let { minOf(0, it - overlayHeight.coerceAtLeast(0)) } ?: 0
        return StickyGroupHeaderState(activeIndex, translation)
    }
}

internal class StickyGroupHeaderController(
    private val scrollView: ScrollView,
    private val contentRoot: ViewGroup,
    overlay: TextView,
) {
    private val overlay = StickyGroupHeaderUi.prepareOverlay(overlay)
    private val headers = mutableListOf<TextView>()
    private var bound = false
    private val scrollLocation = IntArray(2)
    private val headerLocation = IntArray(2)
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener(::update)
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        refreshHeaders()
        update()
    }

    fun bind() {
        if (bound) return
        bound = true
        contentRoot.addOnLayoutChangeListener(layoutListener)
        scrollView.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        refreshHeaders()
        scrollView.post(::update)
    }

    fun release() {
        if (!bound) return
        bound = false
        contentRoot.removeOnLayoutChangeListener(layoutListener)
        val observer = scrollView.viewTreeObserver
        if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
        hide()
        headers.clear()
    }

    private fun refreshHeaders() {
        headers.clear()
        collectHeaders(contentRoot)
    }

    private fun collectHeaders(view: View) {
        if (view is TextView && StickyGroupHeaderUi.isMarked(view)) headers += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectHeaders(view.getChildAt(index))
        }
    }

    private fun update() {
        if (!bound || !scrollView.isAttachedToWindow) {
            hide()
            return
        }
        if (headers.isEmpty() || headers.any { !it.isAttachedToWindow }) refreshHeaders()
        if (headers.isEmpty()) {
            hide()
            return
        }
        scrollView.getLocationOnScreen(scrollLocation)
        val visibleHeaders = headers.filter {
            it.isAttachedToWindow && it.visibility == View.VISIBLE && it.height > 0
        }
        if (visibleHeaders.isEmpty()) {
            hide()
            return
        }
        val tops = visibleHeaders.map { header ->
            header.getLocationOnScreen(headerLocation)
            headerLocation[1] - scrollLocation[1]
        }
        val state = StickyGroupHeaderPolicy.resolve(tops, overlay.height) ?: run {
            hide()
            return
        }
        val active = visibleHeaders[state.activeIndex]
        if (overlay.text != active.text) overlay.text = active.text
        overlay.translationY = state.translationY.toFloat()
        overlay.visibility = View.VISIBLE
        if (overlay.height == 0) overlay.post(::update)
    }

    private fun hide() {
        overlay.translationY = 0f
        overlay.visibility = View.GONE
    }
}

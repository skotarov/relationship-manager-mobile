package com.onlineimoti.calllog

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Detaches the Home timeline while the filtered full log temporarily owns the same container. */
internal class HomeFullLogReturnUi(activity: Activity) {
    private val container = activity.findViewById<LinearLayout>(R.id.homeCallsContainer)
    private val scrollView = activity.findViewById<ScrollView>(R.id.homeCallsScrollView)

    fun capture(): Snapshot {
        val scrollY = scrollView.scrollY
        val children = ArrayList<View>(container.childCount)
        while (container.childCount > 0) {
            val child = container.getChildAt(0)
            container.removeViewAt(0)
            children += child
        }
        return Snapshot(children, scrollY)
    }

    fun restore(snapshot: Snapshot) {
        container.removeAllViews()
        snapshot.children.forEach { child -> container.addView(child) }
        val targetScrollY = snapshot.scrollY.coerceAtLeast(0)
        scrollView.post { scrollView.scrollTo(0, targetScrollY) }
    }

    internal data class Snapshot(
        val children: List<View>,
        val scrollY: Int,
    )
}

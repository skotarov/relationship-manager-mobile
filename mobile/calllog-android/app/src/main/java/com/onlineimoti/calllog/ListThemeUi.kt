package com.onlineimoti.calllog

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import java.util.Collections
import java.util.WeakHashMap

/** Visual-only list density shared by Call Log, History, SMS and CRM lists. */
internal object ListThemeUi {
    private val compactRows = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val originalCornerRadii = WeakHashMap<View, Float>()

    fun isCompact(context: Context): Boolean {
        return ConfigStore.load(context.applicationContext).listTheme == ConfigStore.LIST_THEME_COMPACT
    }

    fun applyRowSpacing(
        view: View,
        context: Context,
        dp: (Int) -> Int,
        normalSpacingDp: Int = 8,
    ): View {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return view
        rememberCornerRadius(view, dp)
        if (isCompact(context)) {
            compactRows.add(view)
            params.topMargin = 0
            params.bottomMargin = 0
            view.layoutParams = params
            view.post {
                (view.parent as? ViewGroup)?.let { parent ->
                    styleCompactGroups(parent, dp)
                }
            }
        } else {
            compactRows.remove(view)
            params.topMargin = 0
            params.bottomMargin = dp(normalSpacingDp)
            view.layoutParams = params
            applyCorners(view, top = true, bottom = true, dp = dp)
        }
        return view
    }

    /**
     * Every uninterrupted sequence of registered rows is one visual group.
     * Date/week title views are not registered, so they automatically split groups.
     */
    private fun styleCompactGroups(parent: ViewGroup, dp: (Int) -> Int) {
        val group = mutableListOf<View>()
        fun flushGroup() {
            if (group.isEmpty()) return
            group.forEachIndexed { index, row ->
                val first = index == 0
                val last = index == group.lastIndex
                val params = row.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    params.topMargin = if (first) 0 else -dp(1)
                    params.bottomMargin = 0
                    row.layoutParams = params
                }
                applyCorners(row, top = first, bottom = last, dp = dp)
            }
            group.clear()
        }

        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (compactRows.contains(child)) {
                group.add(child)
            } else {
                flushGroup()
            }
        }
        flushGroup()
    }

    private fun rememberCornerRadius(view: View, dp: (Int) -> Int) {
        if (originalCornerRadii.containsKey(view)) return
        val radius = when (view) {
            is MaterialCardView -> view.radius
            else -> (view.background as? GradientDrawable)?.cornerRadius ?: 0f
        }.takeIf { it > 0f } ?: dp(12).toFloat()
        originalCornerRadii[view] = radius
    }

    private fun applyCorners(view: View, top: Boolean, bottom: Boolean, dp: (Int) -> Int) {
        val radius = originalCornerRadii[view] ?: dp(12).toFloat()
        val topRadius = if (top) radius else 0f
        val bottomRadius = if (bottom) radius else 0f
        when (view) {
            is MaterialCardView -> {
                view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                    .setTopLeftCornerSize(topRadius)
                    .setTopRightCornerSize(topRadius)
                    .setBottomRightCornerSize(bottomRadius)
                    .setBottomLeftCornerSize(bottomRadius)
                    .build()
            }
            else -> {
                val background = view.background?.mutate() as? GradientDrawable ?: return
                background.cornerRadii = floatArrayOf(
                    topRadius, topRadius,
                    topRadius, topRadius,
                    bottomRadius, bottomRadius,
                    bottomRadius, bottomRadius,
                )
                view.background = background
            }
        }
    }
}

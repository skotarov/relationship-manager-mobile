package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/** Borderless CRM clients action used in the fixed Home bottom bar. */
class HomeCrmModeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {

    init {
        strokeWidth = 0
        elevation = 0f
        stateListAnimator = null
        minimumWidth = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
    }

    override fun setBackgroundTintList(backgroundTintList: ColorStateList?) {
        val normalized = if (backgroundTintList?.defaultColor == Color.WHITE) {
            ColorStateList.valueOf(Color.TRANSPARENT)
        } else {
            backgroundTintList
        }
        super.setBackgroundTintList(normalized)
    }
}
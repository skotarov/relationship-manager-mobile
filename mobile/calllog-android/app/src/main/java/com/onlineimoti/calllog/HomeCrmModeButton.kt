package com.onlineimoti.calllog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

/**
 * HomeActivity uses white as its inactive button tint. On the Call Log surface
 * that should be visually transparent, while the active CRM state stays blue.
 */
class HomeCrmModeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle,
) : MaterialButton(context, attrs, defStyleAttr) {
    override fun setBackgroundTintList(backgroundTintList: ColorStateList?) {
        val normalized = if (backgroundTintList?.defaultColor == Color.WHITE) {
            ColorStateList.valueOf(Color.TRANSPARENT)
        } else {
            backgroundTintList
        }
        super.setBackgroundTintList(normalized)
    }
}

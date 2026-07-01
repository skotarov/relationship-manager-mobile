package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

class RelationshipManagerWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    override fun onDraw(canvas: Canvas) {
        val mark = drawable ?: return
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val sourceWidth = mark.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: 1100f
        val sourceHeight = mark.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: 270f
        val fit = min(availableWidth / sourceWidth, availableHeight / sourceHeight)
        val drawWidth = sourceWidth * fit
        val drawHeight = sourceHeight * fit
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        mark.setBounds(0, 0, drawWidth.roundToInt(), drawHeight.roundToInt())

        val wordLeft = drawWidth * (10f / 1100f)
        val wordBottom = drawHeight * (190f / 270f)

        canvas.save()
        canvas.translate(left + wordLeft, top + wordBottom)
        canvas.scale(2f / 3f, 2f / 3f)
        canvas.translate(-wordLeft, -wordBottom)
        canvas.clipRect(0f, 0f, drawWidth, wordBottom)
        mark.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.translate(left, top)
        canvas.clipRect(0f, wordBottom, drawWidth, drawHeight)
        mark.draw(canvas)
        canvas.restore()
    }
}

package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

/** Draws the two vector wordmark paths with independently tuned scale and alignment. */
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

        val sourceWidth = mark.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: VIEWPORT_WIDTH
        val sourceHeight = mark.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: VIEWPORT_HEIGHT
        val fit = min(availableWidth / sourceWidth, availableHeight / sourceHeight)
        val drawWidth = sourceWidth * fit
        val drawHeight = sourceHeight * fit
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        mark.setBounds(0, 0, drawWidth.roundToInt(), drawHeight.roundToInt())

        drawRelationship(canvas, mark, left, top, drawWidth, drawHeight)
        drawManager(canvas, mark, left, top, drawWidth, drawHeight)
    }

    private fun drawRelationship(
        canvas: Canvas,
        mark: android.graphics.drawable.Drawable,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
    ) {
        val relationshipLeft = drawWidth * (RELATIONSHIP_LEFT / VIEWPORT_WIDTH)
        val relationshipBottom = drawHeight * (RELATIONSHIP_BOTTOM / VIEWPORT_HEIGHT)
        canvas.save()
        canvas.translate(left + relationshipLeft, top + relationshipBottom)
        canvas.scale(RELATIONSHIP_SCALE, RELATIONSHIP_SCALE)
        canvas.translate(-relationshipLeft, -relationshipBottom)
        canvas.clipRect(0f, 0f, drawWidth, relationshipBottom)
        mark.draw(canvas)
        canvas.restore()
    }

    private fun drawManager(
        canvas: Canvas,
        mark: android.graphics.drawable.Drawable,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
    ) {
        val sourceLeft = drawWidth * (MANAGER_LEFT / VIEWPORT_WIDTH)
        val sourceTop = drawHeight * (MANAGER_TOP / VIEWPORT_HEIGHT)
        val scaledRelationshipRight = drawWidth * (
            RELATIONSHIP_LEFT + (RELATIONSHIP_P_RIGHT - RELATIONSHIP_LEFT) * RELATIONSHIP_SCALE
        ) / VIEWPORT_WIDTH
        val targetLeft = scaledRelationshipRight + drawWidth * (MANAGER_RIGHT_OFFSET / VIEWPORT_WIDTH)
        val targetTop = sourceTop - drawHeight * (MANAGER_LIFT / VIEWPORT_HEIGHT)

        canvas.save()
        canvas.translate(left + targetLeft, top + targetTop)
        canvas.scale(MANAGER_SCALE, MANAGER_SCALE)
        canvas.translate(-sourceLeft, -sourceTop)
        canvas.clipRect(0f, sourceTop, drawWidth, drawHeight)
        mark.draw(canvas)
        canvas.restore()
    }

    private companion object {
        private const val VIEWPORT_WIDTH = 1100f
        private const val VIEWPORT_HEIGHT = 270f

        private const val RELATIONSHIP_LEFT = 10f
        private const val RELATIONSHIP_P_RIGHT = 995f
        private const val RELATIONSHIP_BOTTOM = 190f
        private const val RELATIONSHIP_SCALE = 2f / 3f

        private const val MANAGER_LEFT = 664f
        private const val MANAGER_TOP = 199f
        private const val MANAGER_SCALE = 0.91f
        private const val MANAGER_RIGHT_OFFSET = 6f
        private const val MANAGER_LIFT = 6f
    }
}

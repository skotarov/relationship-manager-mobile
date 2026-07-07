package com.onlineimoti.calllog

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View.MeasureSpec
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a compact two-line wordmark. Its visible width is the width of
 * "Relationship"; "Manager" is right-aligned beneath it with extra breathing
 * room at the right edge.
 */
class RelationshipManagerWordmarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    /**
     * For wrap-content use the painted wordmark width instead of the much wider
     * source SVG viewport. This lets the adjacent CRM button sit immediately
     * after the final visible logo letter.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val horizontalPadding = paddingLeft + paddingRight
        val verticalPadding = paddingTop + paddingBottom
        val fallbackContentHeight = (48f * resources.displayMetrics.density).roundToInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        var contentHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> (heightSize - verticalPadding).coerceAtLeast(0)
            MeasureSpec.AT_MOST -> min(fallbackContentHeight, (heightSize - verticalPadding).coerceAtLeast(0))
            else -> fallbackContentHeight
        }
        val visibleRatio = relationshipRight() / VIEWPORT_HEIGHT
        var desiredWidth = horizontalPadding + (contentHeight * visibleRatio).roundToInt()
        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(desiredWidth, widthSize)
            else -> desiredWidth
        }
        if (heightMode != MeasureSpec.EXACTLY && measuredWidth < desiredWidth) {
            contentHeight = ((measuredWidth - horizontalPadding).coerceAtLeast(0) / visibleRatio).roundToInt()
            desiredWidth = horizontalPadding + (contentHeight * visibleRatio).roundToInt()
        }
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(verticalPadding + contentHeight, heightSize)
            else -> verticalPadding + contentHeight
        }
        setMeasuredDimension(measuredWidth.coerceAtLeast(0), measuredHeight.coerceAtLeast(0))
    }

    override fun onDraw(canvas: Canvas) {
        val mark = drawable ?: return
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val sourceWidth = mark.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: VIEWPORT_WIDTH
        val sourceHeight = mark.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: VIEWPORT_HEIGHT
        val relationshipWidthAtUnitScale = sourceWidth * (relationshipRight() / VIEWPORT_WIDTH)
        val fit = min(
            availableHeight / sourceHeight,
            availableWidth / relationshipWidthAtUnitScale,
        )
        val drawWidth = sourceWidth * fit
        val drawHeight = sourceHeight * fit
        val visibleWidth = drawWidth * (relationshipRight() / VIEWPORT_WIDTH)
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        mark.setBounds(0, 0, drawWidth.roundToInt(), drawHeight.roundToInt())

        drawRelationship(canvas, mark, left, top, drawWidth, drawHeight, visibleWidth)
        drawManager(canvas, mark, left, top, drawWidth, drawHeight, visibleWidth)
    }

    private fun drawRelationship(
        canvas: Canvas,
        mark: Drawable,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        visibleWidth: Float,
    ) {
        val relationshipLeft = drawWidth * (RELATIONSHIP_LEFT / VIEWPORT_WIDTH)
        val relationshipBottom = drawHeight * (RELATIONSHIP_BOTTOM / VIEWPORT_HEIGHT)
        canvas.save()
        canvas.clipRect(left, top, left + visibleWidth, top + relationshipBottom)
        canvas.translate(left + relationshipLeft, top + relationshipBottom)
        canvas.scale(RELATIONSHIP_SCALE, RELATIONSHIP_SCALE)
        canvas.translate(-relationshipLeft, -relationshipBottom)
        mark.draw(canvas)
        canvas.restore()
    }

    private fun drawManager(
        canvas: Canvas,
        mark: Drawable,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        visibleWidth: Float,
    ) {
        val sourceTop = drawHeight * (MANAGER_TOP / VIEWPORT_HEIGHT)
        val sourceRight = drawWidth * (MANAGER_RIGHT / VIEWPORT_WIDTH)
        val relationshipBottom = drawHeight * (RELATIONSHIP_BOTTOM / VIEWPORT_HEIGHT)
        val rightPadding = drawWidth * (MANAGER_RIGHT_PADDING / VIEWPORT_WIDTH)
        val targetRight = visibleWidth - rightPadding
        val targetTop = sourceTop - drawHeight * (MANAGER_LIFT / VIEWPORT_HEIGHT)
        val managerClipTop = relationshipBottom - drawHeight * (MANAGER_OVERLAP_ALLOWANCE / VIEWPORT_HEIGHT)

        canvas.save()
        canvas.clipRect(left, top + managerClipTop, left + visibleWidth, top + drawHeight)
        canvas.translate(left + targetRight, top + targetTop)
        canvas.scale(MANAGER_SCALE, MANAGER_SCALE)
        canvas.translate(-sourceRight, -sourceTop)
        mark.draw(canvas)
        canvas.restore()
    }

    private fun relationshipRight(): Float =
        RELATIONSHIP_LEFT + (RELATIONSHIP_P_RIGHT - RELATIONSHIP_LEFT) * RELATIONSHIP_SCALE

    private companion object {
        private const val VIEWPORT_WIDTH = 1100f
        private const val VIEWPORT_HEIGHT = 270f

        private const val RELATIONSHIP_LEFT = 10f
        private const val RELATIONSHIP_P_RIGHT = 995f
        private const val RELATIONSHIP_BOTTOM = 190f
        private const val RELATIONSHIP_SCALE = 2f / 3f

        private const val MANAGER_TOP = 199f
        private const val MANAGER_RIGHT = 1065f
        private const val MANAGER_SCALE = 0.91f
        private const val MANAGER_RIGHT_PADDING = 96f
        private const val MANAGER_LIFT = 12f
        private const val MANAGER_OVERLAP_ALLOWANCE = 8f
    }
}

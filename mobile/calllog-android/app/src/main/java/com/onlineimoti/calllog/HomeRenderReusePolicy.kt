package com.onlineimoti.calllog

/** Skips rebuilding Home only when unchanged data still has visible rendered rows. */
internal object HomeRenderReusePolicy {
    fun canReuseExistingRows(
        unchanged: Boolean,
        forceRender: Boolean,
        hasRenderedRows: Boolean,
    ): Boolean = unchanged && !forceRender && hasRenderedRows
}

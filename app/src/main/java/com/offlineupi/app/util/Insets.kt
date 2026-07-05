package com.offlineupi.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Consumes system-bar (status + navigation) insets as padding on [root] so content
 * does not draw under the status bar or gesture nav. Required because targetSdk 35
 * (Android 15) enforces edge-to-edge windows.
 *
 * @param top    apply the status-bar inset as top padding (default true)
 * @param bottom apply the navigation-bar inset as bottom padding (default true)
 */
fun applySystemBarInsets(root: View, top: Boolean = true, bottom: Boolean = true) {
    val basePadTop = root.paddingTop
    val basePadBottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.updatePadding(
            top = if (top) basePadTop + bars.top else basePadTop,
            bottom = if (bottom) basePadBottom + bars.bottom else basePadBottom,
        )
        insets
    }
}

package com.github.andreyasadchy.xtra.util

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/** Reserve the top safe area outside scrolling headers, once for the whole browser. */
object BrowseInsets {
    fun install(navHost: View, ignoreCutouts: Boolean) {
        ViewCompat.setOnApplyWindowInsetsListener(navHost) { view, insets ->
            val types = WindowInsetsCompat.Type.systemBars() or
                (if (ignoreCutouts) 0 else WindowInsetsCompat.Type.displayCutout())
            val top = insets.getInsets(types).top
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = top
            }
            // Fragment inset listeners must not add the same top space to their toolbars.
            // Other sides, visibility, and the player's separate inset dispatch stay intact.
            WindowInsetsCompat.Builder(insets).apply {
                for (type in intArrayOf(
                    WindowInsetsCompat.Type.statusBars(),
                    WindowInsetsCompat.Type.navigationBars(),
                    WindowInsetsCompat.Type.captionBar(),
                    WindowInsetsCompat.Type.displayCutout(),
                )) {
                    val visible = insets.getInsets(type)
                    val stable = insets.getInsetsIgnoringVisibility(type)
                    setInsets(type, Insets.of(visible.left, 0, visible.right, visible.bottom))
                    setInsetsIgnoringVisibility(type, Insets.of(stable.left, 0, stable.right, stable.bottom))
                }
            }.build()
        }
        ViewCompat.requestApplyInsets(navHost)
    }
}

package com.github.andreyasadchy.xtra.util

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import com.github.andreyasadchy.xtra.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.tabs.TabLayout
import kotlin.math.roundToInt

/** Compact browsing controls without recreating the activity or interrupting playback. */
object BrowseChrome {
    private data class OriginalSize(val height: Int, val minimumHeight: Int)
    private data class OriginalNavigation(val labels: Int, val paddingTop: Int, val paddingBottom: Int)

    fun update(root: View) {
        val landscape = root.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        update(root, landscape)
    }

    private fun update(view: View, landscape: Boolean) {
        val heightDp = when (view) {
            is Toolbar, is BottomNavigationView -> 48
            is TabLayout -> 42
            else -> null
        }
        if (heightDp != null) {
            val original = view.getTag(R.id.browse_chrome_size) as? OriginalSize
                ?: OriginalSize(view.layoutParams.height, view.minimumHeight).also {
                    view.setTag(R.id.browse_chrome_size, it)
                }
            val height = (heightDp * view.resources.displayMetrics.density).roundToInt()
            view.minimumHeight = if (landscape) height else original.minimumHeight
            val target = if (landscape) height else original.height
            if (view.layoutParams.height != target) {
                view.layoutParams = view.layoutParams.apply { this.height = target }
            }
            if (view is BottomNavigationView) {
                val navigation = view.getTag(R.id.browse_chrome_navigation) as? OriginalNavigation
                    ?: OriginalNavigation(view.labelVisibilityMode, view.itemPaddingTop, view.itemPaddingBottom).also {
                        view.setTag(R.id.browse_chrome_navigation, it)
                    }
                val padding = (4 * view.resources.displayMetrics.density).roundToInt()
                view.labelVisibilityMode = if (landscape) NavigationBarView.LABEL_VISIBILITY_UNLABELED else navigation.labels
                view.itemPaddingTop = if (landscape) padding else navigation.paddingTop
                view.itemPaddingBottom = if (landscape) padding else navigation.paddingBottom
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) update(view.getChildAt(index), landscape)
        }
    }
}

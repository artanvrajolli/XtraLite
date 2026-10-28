package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.github.andreyasadchy.xtra.R
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.tabs.TabLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Reuses the existing controls and listeners, restoring their parents in portrait. */
internal class SingleRowBrowseHeader private constructor(private val appBar: AppBarLayout) {
    private val toolbar = appBar.findViewById<Toolbar>(R.id.toolbar)
    private val tabs = appBar.findViewById<TabLayout?>(R.id.tabLayout)
    private val sort = appBar.findViewById<View?>(R.id.sortBar)
    private val parent = toolbar.parent as ViewGroup
    private data class Position(val view: View, val parent: ViewGroup, val index: Int, val params: ViewGroup.LayoutParams)
    private val positions = listOfNotNull(toolbar, tabs, sort).map {
        val parent = it.parent as ViewGroup
        Position(it, parent, parent.indexOfChild(it), it.layoutParams)
    }
    private val collapsing = parent as? CollapsingToolbarLayout
    private val titleEnabled = collapsing?.isTitleEnabled
    private val originalHeight = parent.layoutParams.height
    private val originalMinimum = parent.minimumHeight
    private val details = appBar.findViewById<View?>(R.id.toolbarContainer)
    private val detailsVisibility = details?.visibility
    private val mode = tabs?.tabMode
    private val gravity = tabs?.tabGravity
    private val sortSummary = sort?.findViewById<View>(R.id.sortText)?.parent as? View
    private val sortLabel = sort?.findViewById<View>(R.id.sortByText)
    private val summaryVisibility = sortSummary?.visibility
    private val labelVisibility = sortLabel?.visibility
    private val sortDescription = sort?.contentDescription
    private val row = HeaderRow(appBar.context, toolbar, tabs, sort)

    private fun attach() {
        positions.forEach { it.parent.removeView(it.view) }
        row.addView(toolbar, CollapsingToolbarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.rowHeight))
        tabs?.let {
            it.tabMode = TabLayout.MODE_AUTO
            it.tabGravity = TabLayout.GRAVITY_CENTER
            row.addView(it, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.rowHeight))
        }
        sort?.let {
            sortSummary?.visibility = View.GONE
            sortLabel?.visibility = View.GONE
            it.contentDescription = appBar.context.getString(R.string.sort)
            row.addView(it, ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.rowHeight))
        }
        if (collapsing != null) {
            collapsing.isTitleEnabled = false
            details?.visibility = View.GONE
            collapsing.minimumHeight = row.rowHeight
            collapsing.layoutParams = collapsing.layoutParams.apply { height = row.rowHeight }
            collapsing.addView(row, positions.first().index, CollapsingToolbarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, row.rowHeight
            ).apply { collapseMode = CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN })
        } else {
            appBar.addView(row, positions.first().index, AppBarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, row.rowHeight
            ).apply { scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS })
            // BrowseInsets reserves the top safe area outside this scrolling app bar.
        }
        appBar.setExpanded(true, false)
        ViewCompat.requestApplyInsets(appBar)
    }

    private fun restore() {
        row.restoreTitles()
        positions.forEach { row.removeView(it.view) }
        parent.removeView(row)
        positions.sortedBy { it.index }.forEach {
            it.parent.addView(it.view, it.index.coerceAtMost(it.parent.childCount), it.params)
        }
        tabs?.let {
            it.tabMode = mode!!
            it.tabGravity = gravity!!
        }
        sortSummary?.visibility = summaryVisibility!!
        sortLabel?.visibility = labelVisibility!!
        sort?.contentDescription = sortDescription
        if (collapsing != null) {
            collapsing.isTitleEnabled = titleEnabled!!
            details?.visibility = detailsVisibility!!
            collapsing.minimumHeight = originalMinimum
            collapsing.layoutParams = collapsing.layoutParams.apply { height = originalHeight }
            // Channel/game pagers size their pinned toolbar around the tabs and sort row.
            appBar.findViewById<View?>(R.id.toolbarContainer2)?.doOnLayout { controls ->
                if (appBar.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    val margins = details?.layoutParams as? ViewGroup.MarginLayoutParams
                    val height = (margins?.topMargin ?: 0) + controls.height
                    if (height > 0) {
                        margins?.bottomMargin = controls.height
                        details?.requestLayout()
                        toolbar.layoutParams = toolbar.layoutParams.apply { this.height = height }
                        collapsing.scrimVisibleHeightTrigger = height + 1
                    }
                }
            }
        }
        appBar.setExpanded(true, false)
        ViewCompat.requestApplyInsets(appBar)
    }

    companion object {
        fun update(appBar: AppBarLayout, landscape: Boolean) {
            val existing = appBar.getTag(R.id.browse_single_row_header) as? SingleRowBrowseHeader
            if (!landscape) {
                existing?.restore()
                appBar.setTag(R.id.browse_single_row_header, null)
            } else if (existing == null) {
                val toolbar = appBar.findViewById<Toolbar?>(R.id.toolbar) ?: return
                // Search keeps its editable search field; browsing pages share one row.
                if (toolbar.findViewById<View?>(R.id.searchView) != null) return
                if (toolbar.parent !is AppBarLayout && toolbar.parent !is CollapsingToolbarLayout) return
                appBar.setTag(R.id.browse_single_row_header, SingleRowBrowseHeader(appBar).also { it.attach() })
            }
        }
    }

    /** Measures independently of the old pager callbacks that assign a tall toolbar. */
    private class HeaderRow(context: Context, private val toolbar: Toolbar, private val tabs: TabLayout?, private val sort: View?) : ViewGroup(context) {
        val rowHeight = (48 * resources.displayMetrics.density).roundToInt()
        private val titles = mutableMapOf<TextView, Int>()
        private var start = 0
        private var end = 0
        private var sortWidth = 0

        fun restoreTitles() {
            titles.forEach { (view, visibility) -> view.visibility = visibility }
            titles.clear()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (tabs?.visibility != View.GONE && tabs != null) {
                for (index in 0 until toolbar.childCount) {
                    val child = toolbar.getChildAt(index)
                    if (child is TextView) {
                        titles.putIfAbsent(child, child.visibility)
                        child.visibility = View.GONE
                    }
                }
            } else restoreTitles()
            val heightSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
            toolbar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
            var menuWidth = 0
            for (index in 0 until toolbar.childCount) {
                val child = toolbar.getChildAt(index)
                if (child is ActionMenuView && child.visibility != View.GONE) menuWidth = child.measuredWidth
            }
            start = max(toolbar.currentContentInsetStart, if (toolbar.navigationIcon != null) rowHeight else 0)
            end = max(toolbar.currentContentInsetEnd, menuWidth)
            sortWidth = if (sort != null && sort.visibility != View.GONE) rowHeight else 0
            val available = (width - start - end - sortWidth).coerceAtLeast(0)
            tabs?.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY), heightSpec)
            sort?.measure(MeasureSpec.makeMeasureSpec(sortWidth, MeasureSpec.EXACTLY), heightSpec)
            setMeasuredDimension(width, rowHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            toolbar.layout(0, 0, width, rowHeight)
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val tabLeft = if (rtl) end + sortWidth else start
            tabs?.layout(tabLeft, 0, tabLeft + tabs.measuredWidth, rowHeight)
            val sortLeft = if (rtl) end else width - end - sortWidth
            sort?.layout(sortLeft, 0, sortLeft + sortWidth, rowHeight)
            sort?.findViewById<View?>(R.id.sortByImage)?.let { icon ->
                val x = (sortWidth - icon.measuredWidth) / 2
                val y = (rowHeight - icon.measuredHeight) / 2
                icon.layout(x, y, x + icon.measuredWidth, y + icon.measuredHeight)
            }
        }
    }
}

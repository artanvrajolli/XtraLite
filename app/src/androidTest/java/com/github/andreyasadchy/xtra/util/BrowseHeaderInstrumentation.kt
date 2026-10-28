package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.tabs.TabLayout
import kotlin.math.roundToInt

/** Device-level measurement checks, including the pager's asynchronous toolbar resizing. */
class BrowseHeaderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    @Suppress("DEPRECATION")
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = activity!!
            var count = 0
            runOnMainSync {
                val original = Configuration(host.resources.configuration)
                try {
                    for (layout in listOf(R.layout.fragment_channel, R.layout.fragment_game_pager, R.layout.fragment_media_pager, R.layout.fragment_games)) {
                        for (widthDp in listOf(600, 900)) {
                            host.resources.updateConfiguration(Configuration(original).apply { orientation = Configuration.ORIENTATION_PORTRAIT }, host.resources.displayMetrics)
                            val root = LayoutInflater.from(host).inflate(layout, null)
                            val toolbar = root.findViewById<Toolbar>(R.id.toolbar)
                            toolbar.title = "Channel title"
                            toolbar.menu.findItem(R.id.followButton)?.isVisible = true
                            val tabs = root.findViewById<TabLayout?>(R.id.tabLayout)
                            tabs?.let {
                                listOf("About", "Suggestions", "Videos", "Clips").forEach { name -> it.addTab(it.newTab().setText(name)) }
                                it.selectTab(it.getTabAt(2))
                            }
                            val sort = root.findViewById<View>(R.id.sortBar)
                            sort.visibility = View.VISIBLE
                            val summary = sort.findViewById<TextView>(R.id.sortText)
                            summary.text = "Sort by: Upload date. Type: All"
                            var clicked = false
                            sort.setOnClickListener { clicked = true }
                            val originalToolbarParent = toolbar.parent
                            val originalTabsParent = tabs?.parent
                            val originalSortParent = sort.parent
                            val density = host.resources.displayMetrics.density
                            val rowHeight = (48 * density).roundToInt()
                            fun measure() {
                                val width = (widthDp * density).roundToInt()
                                val height = (400 * density).roundToInt()
                                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                                root.layout(0, 0, width, height)
                            }
                            measure() // Populate CollapsingToolbarLayout's cached toolbar before moving it.
                            repeat(3) {
                                host.resources.updateConfiguration(Configuration(original).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }, host.resources.displayMetrics)
                                BrowseChrome.update(root)
                                measure()
                                // Reproduce the late page-change callback that defeated the old fix.
                                toolbar.layoutParams = toolbar.layoutParams.apply { height = rowHeight * 3 }
                                measure()
                                val appBar = root.findViewById<AppBarLayout>(R.id.appBar)
                                check(appBar.measuredHeight == rowHeight) { "Header has ${appBar.measuredHeight}px, expected $rowHeight (layout $layout)" }
                                check(toolbar.measuredHeight == rowHeight)
                                check(root.findViewById<CollapsingToolbarLayout?>(R.id.collapsingToolbar)?.isTitleEnabled != true)
                                check(toolbar.parent == sort.parent && (tabs == null || tabs.parent == toolbar.parent))
                                check(tabs == null || tabs.top == toolbar.top && tabs.measuredHeight == rowHeight)
                                check(sort.top == toolbar.top && sort.measuredWidth == rowHeight)
                                check(tabs == null || tabs.right <= sort.left) { "Tabs overlap sort button" }
                                sort.performClick()
                                check(clicked) { "Sort listener was lost" }
                                host.resources.updateConfiguration(Configuration(original).apply { orientation = Configuration.ORIENTATION_PORTRAIT }, host.resources.displayMetrics)
                                BrowseChrome.update(root)
                                measure()
                                check(toolbar.parent == originalToolbarParent && tabs?.parent == originalTabsParent && sort.parent == originalSortParent)
                                check((summary.parent as View).visibility == View.VISIBLE)
                                check(tabs == null || tabs.selectedTabPosition == 2)
                            }
                            count++
                        }
                    }
                    // Editable search retains its original toolbar and query field.
                    val search = LayoutInflater.from(host).inflate(R.layout.fragment_search, null)
                    val toolbar = search.findViewById<Toolbar>(R.id.toolbar)
                    val parent = toolbar.parent
                    host.resources.updateConfiguration(Configuration(original).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }, host.resources.displayMetrics)
                    BrowseChrome.update(search)
                    check(toolbar.parent == parent)
                    count++
                } finally {
                    host.resources.updateConfiguration(original, host.resources.displayMetrics)
                }
            }
            result.putString("stream", "Passed $count header scenarios, including repeated rotation and late toolbar resizing.\n")
            runOnMainSync { host.finish() }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            activity?.let { runOnMainSync { it.finish() } }
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

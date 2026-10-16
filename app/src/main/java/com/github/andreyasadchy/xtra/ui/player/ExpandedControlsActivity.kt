package com.github.andreyasadchy.xtra.ui.player

import android.view.Menu
import android.view.MenuItem
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.CastManager
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity

/** Full screen remote for the video playing on the TV. */
class ExpandedControlsActivity : ExpandedControllerActivity() {

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.cast_expanded_controller, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.mediaRouteMenuItem)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.switchTv) {
            CastManager.showDeviceSwitcher(this)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}

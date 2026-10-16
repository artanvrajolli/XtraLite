package com.github.andreyasadchy.xtra.util

import android.app.Activity
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

/**
 * App-wide cast session handling: stops the local relay when casting ends and moves the current
 * video to another TV when the user switches devices. All methods must be called on the main thread.
 */
object CastManager {

    private var castContext: CastContext? = null
    private var pendingLoad: MediaLoadRequestData? = null

    /** True while the current video is being moved to another TV. */
    var isSwitching = false
        private set

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            pendingLoad?.let { session.remoteMediaClient?.load(it) }
            pendingLoad = null
            isSwitching = false
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingLoad = null
            isSwitching = false
            CastProxyServer.stop()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (!isSwitching) {
                CastProxyServer.stop()
            }
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun init(castContext: CastContext) {
        if (this.castContext == null) {
            this.castContext = castContext
            castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    /** Shows the list of TVs; picking another one continues the current video there. */
    fun showDeviceSwitcher(activity: Activity) {
        val castContext = castContext ?: return
        val selector = castContext.mergedSelector ?: return
        val router = MediaRouter.getInstance(activity)
        val previousRoute = router.selectedRoute.id
        val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
        val mediaInfo = client?.mediaInfo
        if (mediaInfo != null) {
            // armed before a TV is picked: the old session can end before the dialog is dismissed
            isSwitching = true
            pendingLoad = MediaLoadRequestData.Builder().apply {
                setMediaInfo(mediaInfo)
                setAutoplay(true)
                if (mediaInfo.streamType != MediaInfo.STREAM_TYPE_LIVE) {
                    setCurrentTime(client.approximateStreamPosition)
                }
            }.build()
        }
        MediaRouteChooserDialog(activity).apply {
            routeSelector = selector
            setOnDismissListener {
                if (router.selectedRoute.id == previousRoute) {
                    isSwitching = false
                    pendingLoad = null
                    if (castContext.sessionManager.currentCastSession == null) {
                        CastProxyServer.stop()
                    }
                }
            }
        }.show()
    }
}

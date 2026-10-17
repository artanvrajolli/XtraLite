package com.github.andreyasadchy.xtra.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouter
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.ui.player.ExpandedControlsActivity
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * App-wide cast session handling: stops the local relay when casting ends and moves the current
 * video to another TV when the user switches devices. All methods must be called on the main thread.
 */
object CastManager {

    const val TYPE_STREAM = "stream"
    const val TYPE_VIDEO = "video"
    const val TYPE_CLIP = "clip"

    /**
     * Something to play on the TV. [sourceUrl] is a Twitch HLS playlist or an MP4 file. The rest
     * identifies the content; it travels with the cast session so progress can be saved and the
     * video continued on this device after casting.
     */
    class CastMedia(
        val sourceUrl: String,
        val type: String,
        val title: String?,
        val channelName: String?,
        val thumbnail: String?,
        val positionMs: Long? = null,
        val channelId: String? = null,
        val channelLogin: String? = null,
        val videoId: String? = null,
        val clipId: String? = null,
    ) {
        val isLive: Boolean
            get() = type == TYPE_STREAM

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            putOpt("title", title)
            putOpt("channelName", channelName)
            putOpt("thumbnail", thumbnail)
            putOpt("channelId", channelId)
            putOpt("channelLogin", channelLogin)
            putOpt("videoId", videoId)
            putOpt("clipId", clipId)
        }
    }

    /** What was playing on the TV when casting stopped, to continue it on this device. */
    class Handoff(val data: JSONObject, val positionMs: Long, val time: Long) {
        val type: String get() = data.optString("type")
        fun string(name: String): String? = data.optString(name).takeIf { it.isNotEmpty() }
    }

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handoff: Handoff? = null
    private var handoffListener: ((Handoff) -> Unit)? = null

    // saves the position of past broadcasts while they play on the TV
    private val progressListener = RemoteMediaClient.ProgressListener { progressMs, _ ->
        castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaInfo?.customData?.let { saveProgress(it, progressMs) }
    }

    private var castContext: CastContext? = null
    private var pendingLoad: MediaLoadRequestData? = null

    /** True while the current video is being moved to another TV. */
    var isSwitching = false
        private set

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            session.remoteMediaClient?.addProgressListener(progressListener, 10_000)
            pendingLoad?.let { session.remoteMediaClient?.load(it) }
            pendingLoad = null
            isSwitching = false
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingLoad = null
            isSwitching = false
            CastProxyServer.stop()
        }

        override fun onSessionEnding(session: CastSession) {
            val client = session.remoteMediaClient ?: return
            client.removeProgressListener(progressListener)
            val data = client.mediaInfo?.customData
            if (data != null && !isSwitching) {
                val position = client.approximateStreamPosition
                saveProgress(data, position)
                handoff = Handoff(data, position, System.currentTimeMillis())
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (!isSwitching) {
                CastProxyServer.stop()
                handoff?.let { handoff ->
                    handoffListener?.let {
                        this@CastManager.handoff = null
                        it(handoff)
                    }
                }
            }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            session.remoteMediaClient?.addProgressListener(progressListener, 10_000)
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun init(context: Context, castContext: CastContext) {
        if (this.castContext == null) {
            this.castContext = castContext
            appContext = context.applicationContext
            castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            // a session restored after the app restarted
            castContext.sessionManager.currentCastSession?.remoteMediaClient?.addProgressListener(progressListener, 10_000)
        }
    }

    /**
     * [listener] is called when casting stops so the video can be continued here. A stop that
     * happened while nothing was listening (app in background) is delivered when one is set,
     * if it was less than 10 minutes ago.
     */
    fun setHandoffListener(listener: ((Handoff) -> Unit)?) {
        handoffListener = listener
        if (listener != null) {
            handoff?.let {
                handoff = null
                if (System.currentTimeMillis() - it.time < 10 * 60 * 1000) {
                    listener(it)
                }
            }
        }
    }

    private fun saveProgress(data: JSONObject, positionMs: Long) {
        val context = appContext ?: return
        val videoId = data.optString("videoId").toLongOrNull()
        if (data.optString("type") == TYPE_VIDEO && videoId != null && positionMs > 0 && context.prefs().getBoolean(C.PLAYER_USE_VIDEO_POSITIONS, true)) {
            scope.launch {
                (context as XtraApp).xtraModule.playerRepository.saveVideoPosition(VideoPosition(videoId, positionMs))
            }
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

    /** True while connected to a TV; new videos should then be sent there instead of played here. */
    fun isConnected(): Boolean {
        return castContext?.sessionManager?.currentCastSession?.isConnected == true
    }

    /**
     * Plays [media] on the connected TV and opens the remote. [onResult] receives whether the TV
     * accepted it; errors are shown as toasts.
     */
    fun load(activity: Activity, media: CastMedia, onResult: (Boolean) -> Unit = {}) {
        val remoteMediaClient = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        if (remoteMediaClient == null) {
            Toast.makeText(activity, R.string.cast_video_unavailable, Toast.LENGTH_SHORT).show()
            onResult(false)
            return
        }
        val isHls = media.sourceUrl.toUri().path?.endsWith(".mp4") != true
        val url = if (isHls) {
            // Twitch playlists don't allow the receiver's origin, so they are relayed through this device
            CastProxyServer.wrap(activity, (activity.applicationContext as XtraApp).xtraModule.okHttpClient.value, media.sourceUrl)
        } else {
            media.sourceUrl
        }
        if (url == null) {
            Toast.makeText(activity, R.string.cast_no_local_network, Toast.LENGTH_LONG).show()
            onResult(false)
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            (media.title?.takeIf { it.isNotBlank() } ?: media.channelName)?.let { putString(MediaMetadata.KEY_TITLE, it) }
            media.channelName?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            media.thumbnail?.takeIf { it.startsWith("http") }?.let {
                val thumbnail = it.replace(Regex("%?\\{width\\}"), "1280").replace(Regex("%?\\{height\\}"), "720")
                addImage(WebImage(thumbnail.toUri()))
            }
        }
        val mediaInfo = MediaInfo.Builder(url).apply {
            setContentUrl(url)
            setStreamType(if (media.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            setMetadata(metadata)
            setCustomData(media.toJson())
            if (isHls) {
                setContentType(MimeTypes.APPLICATION_M3U8)
                // the relay only offers H.264 variants, which Twitch serves as MPEG-TS
                setHlsSegmentFormat(HlsSegmentFormat.TS)
                setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
            } else {
                setContentType(MimeTypes.VIDEO_MP4)
            }
        }.build()
        remoteMediaClient.load(
            MediaLoadRequestData.Builder().apply {
                setMediaInfo(mediaInfo)
                setAutoplay(true)
                if (!media.isLive) {
                    media.positionMs?.let { setCurrentTime(it) }
                }
            }.build()
        ).setResultCallback { result ->
            if (result.status.isSuccess) {
                activity.startActivity(Intent(activity, ExpandedControlsActivity::class.java))
            } else {
                Toast.makeText(activity, activity.getString(R.string.cast_load_failed, result.status.statusCode), Toast.LENGTH_LONG).show()
            }
            onResult(result.status.isSuccess)
        }
    }

    /** Playlist for a live channel: the first working custom (ad-blocking) proxy, else Twitch. */
    suspend fun getStreamUrl(context: Context, channelLogin: String): String? = withContext(Dispatchers.IO) {
        val xtraModule = (context.applicationContext as XtraApp).xtraModule
        val prefs = context.prefs()
        if (prefs.getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)) {
            val client = xtraModule.okHttpClient.value.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
            xtraModule.playerRepository.getCustomProxies()
                .filter { it.enabled && !it.url.isNullOrBlank() }
                .sortedBy { it.position }
                .forEach { proxy ->
                    val url = TwitchApiHelper.getCustomProxyUrl(proxy, channelLogin, "h264") ?: return@forEach
                    val works = try {
                        client.newCall(Request.Builder().url(url).build()).execute().use {
                            it.isSuccessful && it.body.string().startsWith("#EXTM3U")
                        }
                    } catch (e: Exception) {
                        false
                    }
                    if (works) return@withContext url
                }
        }
        try {
            xtraModule.playerRepository.loadStreamPlaylistUrl(
                context = context,
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context, prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                channelLogin = channelLogin,
                platform = prefs.getString(C.TOKEN_PLATFORM, "web"),
                playerType = prefs.getString(C.TOKEN_PLAYER_TYPE, "site"),
                supportedCodecs = "h264",
                proxyPlaybackAccessToken = false,
                proxyHost = null,
                proxyPort = null,
                proxyUser = null,
                proxyPassword = null,
                proxyTimeout = null,
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getVideoUrl(context: Context, videoId: String): String? = withContext(Dispatchers.IO) {
        val prefs = context.prefs()
        try {
            (context.applicationContext as XtraApp).xtraModule.playerRepository.loadVideoPlaylistUrl(
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context, prefs.getBoolean(C.TOKEN_INCLUDE_TOKEN_VIDEO, true)),
                videoId = videoId,
                supportedCodecs = "h264",
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            ).first
        } catch (e: Exception) {
            null
        }
    }

    /** Best H.264 MP4 of a clip. */
    suspend fun getClipUrl(context: Context, clipId: String): String? = withContext(Dispatchers.IO) {
        val prefs = context.prefs()
        try {
            (context.applicationContext as XtraApp).xtraModule.playerRepository.loadClipQualities(
                networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(context),
                clipId = clipId,
                supportedCodecs = "h264",
                enableIntegrity = prefs.getBoolean(C.ENABLE_INTEGRITY, false),
            )?.filter { !it.url.isNullOrBlank() && (it.codecs == null || it.codecs.startsWith("avc1")) }
                ?.maxWithOrNull(compareBy({ it.resolution ?: 0 }, { it.frameRate ?: 0f }))?.url
        } catch (e: Exception) {
            null
        }
    }
}

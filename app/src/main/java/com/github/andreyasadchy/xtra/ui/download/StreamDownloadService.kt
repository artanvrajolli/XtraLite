package com.github.andreyasadchy.xtra.ui.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.DownloadProgress
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StreamDownloadService : LifecycleService() {

    lateinit var xtraModule: XtraModule
    private val okHttpClient = lazy {
        xtraModule.okHttpClient.value.newBuilder().apply {
            connectTimeout(5, TimeUnit.MINUTES)
            writeTimeout(5, TimeUnit.MINUTES)
            readTimeout(5, TimeUnit.MINUTES)
        }.build()
    }

    private var notificationManager: NotificationManager? = null
    private val downloadJobs = mutableListOf<DownloadJob>()
    private val offlineVideos = mutableListOf<OfflineVideo>()
    val activeDownloads = mutableListOf<DownloadProgress>()
    var listener: Listener? = null

    class DownloadJob(
        var id: Int,
        var job: Job? = null,
    )

    interface Listener {
        fun unbind()
    }

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
    }

    private fun start(videoId: Int) {
        if (activeDownloads.find { it.id == videoId } == null) {
            val downloadJob = DownloadJob(videoId)
            lifecycleScope.launch(Dispatchers.IO) {
                val offlineVideo = xtraModule.offlineVideosRepository.getById(videoId)
                if (offlineVideo != null) {
                    val downloadProgress = DownloadProgress(
                        id = videoId,
                        bytes = offlineVideo.bytes,
                        lastSegmentUrl = offlineVideo.lastSegmentUrl,
                    )
                    offlineVideos.add(offlineVideo)
                    activeDownloads.add(downloadProgress)
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = OfflineVideo.STATUS_WAITING_FOR_STREAM
                    })
                    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val channelId = getString(R.string.notification_downloads_channel_id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                        notificationManager?.createNotificationChannel(
                            NotificationChannel(
                                channelId,
                                ContextCompat.getString(this@StreamDownloadService, R.string.notification_downloads_channel_title),
                                NotificationManager.IMPORTANCE_DEFAULT
                            ).apply {
                                setSound(null, null)
                            }
                        )
                    }
                    sendNotification(offlineVideo, downloadProgress)
                    val done = try {
                        val channelLogin = offlineVideo.channelLogin!!
                        downloadStream(offlineVideo, downloadProgress, downloadJob, channelLogin)
                        true
                    } catch (e: CancellationException) {
                        ensureActive()
                        false
                    } catch (e: Exception) {
                        Log.e("StreamDownloadService", "Download failed", e)
                        false
                    }
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = if (done) {
                            OfflineVideo.STATUS_DOWNLOADED
                        } else {
                            val waitForWifi = if (prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                                val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                                networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            } else false
                            if (waitForWifi) {
                                OfflineVideo.STATUS_WAITING_FOR_WIFI
                            } else {
                                OfflineVideo.STATUS_PENDING
                            }
                        }
                        bytes = downloadProgress.bytes
                        lastSegmentUrl = downloadProgress.lastSegmentUrl
                    })
                    val nextOfflineVideo = offlineVideos.firstOrNull()
                    val nextDownload = activeDownloads.firstOrNull()
                    if (nextOfflineVideo != null && nextDownload != null) {
                        sendNotification(nextOfflineVideo, nextDownload)
                        notificationManager?.cancel(videoId)
                    } else {
                        listener?.unbind()
                        stopSelf()
                    }
                }
            }.also {
                it.invokeOnCompletion {
                    downloadJobs.remove(downloadJob)
                }
                downloadJob.job = it
                downloadJobs.add(downloadJob)
            }
        }
    }

    private suspend fun downloadStream(currentOfflineVideo: OfflineVideo, currentDownloadProgress: DownloadProgress, downloadJob: DownloadJob, channelLogin: String) = withContext(Dispatchers.IO) {
        val offlineCheck = max(prefs().getString(C.DOWNLOAD_STREAM_OFFLINE_CHECK, "10")?.toLongOrNull() ?: 10L, 2L) * 1000L
        val startWait = (prefs().getString(C.DOWNLOAD_STREAM_START_WAIT, "120")?.toLongOrNull())?.times(60000L)
        val endWait = (prefs().getString(C.DOWNLOAD_STREAM_END_WAIT, "15")?.toLongOrNull())?.times(60000L)
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(this@StreamDownloadService, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true))
        val platform = prefs().getString(C.TOKEN_PLATFORM, "web")
        val playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site")
        val supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264")
        val useCustomProxy = prefs().getBoolean(C.PLAYER_USE_CUSTOM_PROXY, true)
        val customProxyList = if (useCustomProxy) {
            xtraModule.playerRepository.getCustomProxies().filter {
                it.enabled && !it.url.isNullOrBlank()
            }.sortedBy { it.position }
        } else null
        var currentCustomProxy = 0
        val useStreamProxy = prefs().getBoolean(C.PLAYER_USE_STREAM_PROXY, false)
        val streamProxyList = if (useStreamProxy) {
            xtraModule.playerRepository.getStreamProxies().filter {
                it.enabled && !it.host.isNullOrBlank() && it.port != null
                        && (it.proxyPlaybackAccessToken || it.proxyMultivariantPlaylist)
            }.sortedBy { it.position }
        } else null
        var currentStreamProxy = 0
        var offlineVideo = currentOfflineVideo
        var downloadProgress = currentDownloadProgress
        val path = offlineVideo.downloadPath!!
        val quality = offlineVideo.quality
        var startTime = System.currentTimeMillis()
        var endTime = startWait?.let { System.currentTimeMillis() + it }
        var playlistUrl = xtraModule.playerRepository.loadStreamPlaylistUrl(this@StreamDownloadService, networkLibrary, gqlHeaders, channelLogin, platform, playerType, supportedCodecs, false, null, null, null, null, null, false)
        while (true) {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                            playlistUrl,
                            xtraModule.cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.decodeToString()
                    } else null
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                            playlistUrl,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            xtraModule.cronetExecutor.value
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.decodeToString()
                    } else null
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.string()
                        } else null
                    }
                }
            }
            if (!playlist.isNullOrBlank()) {
                var proxyUrl = if (useCustomProxy) {
                    customProxyList?.getOrNull(currentCustomProxy)?.let { proxy ->
                        proxy.url?.let { proxyUrl ->
                            (proxyUrl.toUri().takeIf { it.host != null } ?: "https://$proxyUrl".toUri()).let { uri ->
                                if (proxy.addQueryParams) {
                                    val source = uri.getQueryParameter("allow_source") == null
                                    val audio = uri.getQueryParameter("allow_audio_only") == null
                                    val lowLatency = uri.getQueryParameter("fast_bread") == null
                                    val unavailable = uri.getQueryParameter("include_unavailable") == null
                                    val codecs = !supportedCodecs.isNullOrBlank() && uri.getQueryParameter("supported_codecs") == null
                                    if (source || audio || lowLatency || unavailable || codecs) {
                                        uri.buildUpon().apply {
                                            if (source) {
                                                appendQueryParameter("allow_source", "true")
                                            }
                                            if (audio) {
                                                appendQueryParameter("allow_audio_only", "true")
                                            }
                                            if (lowLatency) {
                                                appendQueryParameter("fast_bread", "true")
                                            }
                                            if (unavailable) {
                                                appendQueryParameter("include_unavailable", "true")
                                            }
                                            if (codecs) {
                                                appendQueryParameter("supported_codecs", supportedCodecs)
                                            }
                                        }.build()
                                    } else uri
                                } else uri
                            }.toString().replace("\$channel", channelLogin)
                        }
                    }
                } else null
                val qualities = if (proxyUrl != null) {
                    var result: List<VideoQuality>
                    while (true) {
                        val proxyTimeout = prefs().getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000
                        val newPlaylist = loadPlaylist(proxyUrl!!, networkLibrary, false, null, null, null, null, proxyTimeout)
                        if (!newPlaylist.isNullOrBlank()) {
                            result = getQualities(newPlaylist).ifEmpty { getQualities(playlist) }
                            break
                        } else {
                            currentCustomProxy += 1
                            proxyUrl = customProxyList?.getOrNull(currentCustomProxy)?.let { proxy ->
                                proxy.url?.let { proxyUrl ->
                                    (proxyUrl.toUri().takeIf { it.host != null } ?: "https://$proxyUrl".toUri()).let { uri ->
                                        if (proxy.addQueryParams) {
                                            val source = uri.getQueryParameter("allow_source") == null
                                            val audio = uri.getQueryParameter("allow_audio_only") == null
                                            val lowLatency = uri.getQueryParameter("fast_bread") == null
                                            val unavailable = uri.getQueryParameter("include_unavailable") == null
                                            val codecs = !supportedCodecs.isNullOrBlank() && uri.getQueryParameter("supported_codecs") == null
                                            if (source || audio || lowLatency || unavailable || codecs) {
                                                uri.buildUpon().apply {
                                                    if (source) {
                                                        appendQueryParameter("allow_source", "true")
                                                    }
                                                    if (audio) {
                                                        appendQueryParameter("allow_audio_only", "true")
                                                    }
                                                    if (lowLatency) {
                                                        appendQueryParameter("fast_bread", "true")
                                                    }
                                                    if (unavailable) {
                                                        appendQueryParameter("include_unavailable", "true")
                                                    }
                                                    if (codecs) {
                                                        appendQueryParameter("supported_codecs", supportedCodecs)
                                                    }
                                                }.build()
                                            } else uri
                                        } else uri
                                    }.toString().replace("\$channel", channelLogin)
                                }
                            }
                            if (proxyUrl == null) {
                                result = getQualities(playlist)
                                break
                            }
                        }
                    }
                    result
                } else {
                    var streamProxy = if (useStreamProxy) {
                        streamProxyList?.getOrNull(currentStreamProxy)
                    } else null
                    val proxyTimeout = prefs().getString(C.PROXY_TIMEOUT, "3000")?.toIntOrNull() ?: 3000
                    if (streamProxy != null) {
                        val proxyHost = streamProxy.host
                        val proxyPort = streamProxy.port
                        val proxyUser = streamProxy.username
                        val proxyPassword = streamProxy.password
                        val playlistUrl = if (streamProxy.proxyPlaybackAccessToken) {
                            var result: String
                            while (true) {
                                val newPlaylistUrl = try {
                                    xtraModule.playerRepository.loadStreamPlaylistUrl(this@StreamDownloadService, networkLibrary, gqlHeaders, channelLogin, platform, playerType, supportedCodecs, true, proxyHost, proxyPort, proxyUser, proxyPassword, proxyTimeout, false)
                                } catch (e: Exception) {
                                    null
                                }
                                if (!newPlaylistUrl.isNullOrBlank()) {
                                    result = newPlaylistUrl
                                    break
                                } else {
                                    currentStreamProxy += 1
                                    streamProxy = streamProxyList?.getOrNull(currentStreamProxy)
                                    if (streamProxy == null || !streamProxy.proxyPlaybackAccessToken) {
                                        result = playlistUrl
                                        break
                                    }
                                }
                            }
                            result
                        } else {
                            playlistUrl
                        }
                        if (streamProxy != null) {
                            if (streamProxy.proxyMultivariantPlaylist) {
                                var result: List<VideoQuality>
                                while (true) {
                                    val newPlaylist = loadPlaylist(playlistUrl, networkLibrary, true, proxyHost, proxyPort, proxyUser, proxyPassword, proxyTimeout)
                                    if (!newPlaylist.isNullOrBlank()) {
                                        result = getQualities(newPlaylist).ifEmpty { getQualities(playlist) }
                                        break
                                    } else {
                                        currentStreamProxy += 1
                                        streamProxy = streamProxyList?.getOrNull(currentStreamProxy)
                                        if (streamProxy == null || !streamProxy.proxyMultivariantPlaylist) {
                                            result = getQualities(playlist)
                                            break
                                        }
                                    }
                                }
                                result
                            } else {
                                val newPlaylist = loadPlaylist(playlistUrl, networkLibrary, false, null, null, null, null, null)
                                if (!newPlaylist.isNullOrBlank()) {
                                    getQualities(newPlaylist).ifEmpty { getQualities(playlist) }
                                } else {
                                    getQualities(playlist)
                                }
                            }
                        } else {
                            getQualities(playlist)
                        }
                    } else {
                        getQualities(playlist)
                    }
                }
                if (qualities.isNotEmpty()) {
                    val selectedQuality = if (!quality.isNullOrBlank()) {
                        val audio = if (quality.startsWith("audio", true)) {
                            qualities.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
                        } else null
                        if (audio != null) {
                            audio
                        } else {
                            val targetQuality = quality.split("p")
                            targetQuality.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
                                val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
                                val last = qualities.lastOrNull { it.name != VideoQuality.AUDIO_ONLY_QUALITY }
                                qualities.find { quality ->
                                    quality.resolution != null
                                            && ((targetResolution == quality.resolution
                                            && targetFps >= (quality.frameRate?.let { fps -> floor(fps) } ?: 30f))
                                            || targetResolution > quality.resolution
                                            || quality == last)
                                }
                            } ?: qualities.first()
                        }
                    } else qualities.first()
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        status = OfflineVideo.STATUS_DOWNLOADING
                    })
                    downloadProgress.isLive = true
                    sendNotification(offlineVideo, downloadProgress)
                    val done = try {
                        download(offlineVideo, downloadProgress, channelLogin, selectedQuality.url!!, path, networkLibrary)
                        true
                    } catch (e: CancellationException) {
                        ensureActive()
                        false
                    } catch (e: Exception) {
                        Log.e("StreamDownloadService", "Download failed", e)
                        false
                    }
                    val waitForWifi = if (prefs().getBoolean(C.DOWNLOAD_WIFI_ONLY, false)) {
                        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                        networkCapabilities != null && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    } else false
                    if (waitForWifi) {
                        throw Exception()
                    }
                    val continueDownloading = endWait == null || endWait > 0
                    if (done) {
                        if (continueDownloading) {
                            offlineVideos.remove(offlineVideo)
                            activeDownloads.remove(downloadProgress)
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_DOWNLOADED
                            })
                            val oldId = offlineVideo.id
                            val newId = xtraModule.offlineVideosRepository.save(OfflineVideo(
                                channelId = offlineVideo.channelId,
                                channelLogin = offlineVideo.channelLogin,
                                channelName = offlineVideo.channelName,
                                channelLogo = offlineVideo.channelLogo,
                                downloadPath = offlineVideo.downloadPath,
                                status = OfflineVideo.STATUS_WAITING_FOR_STREAM,
                                quality = offlineVideo.quality,
                                live = true
                            )).toInt()
                            val newVideo = xtraModule.offlineVideosRepository.getById(newId)!!
                            val newDownloadProgress = DownloadProgress(
                                id = newId,
                                bytes = offlineVideo.bytes,
                                lastSegmentUrl = offlineVideo.lastSegmentUrl,
                            )
                            offlineVideo = newVideo
                            downloadProgress = newDownloadProgress
                            downloadJob.id = newId
                            offlineVideos.add(offlineVideo)
                            activeDownloads.add(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
                            notificationManager?.cancel(oldId)
                        }
                    } else {
                        if (continueDownloading) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_WAITING_FOR_STREAM
                            })
                        }
                    }
                    endTime = endWait?.let { System.currentTimeMillis() + it }
                    if (continueDownloading) {
                        playlistUrl = xtraModule.playerRepository.loadStreamPlaylistUrl(this@StreamDownloadService, networkLibrary, gqlHeaders, channelLogin, platform, playerType, supportedCodecs, false, null, null, null, null, null, false)
                    }
                }
            }
            val currentTime = System.currentTimeMillis()
            if (endTime == null || currentTime < endTime) {
                val timeTaken = currentTime - startTime
                if (timeTaken < offlineCheck) {
                    delay((offlineCheck - timeTaken).milliseconds)
                }
                startTime = System.currentTimeMillis()
            } else {
                break
            }
        }
    }

    private suspend fun loadPlaylist(playlistUrl: String, networkLibrary: String?, useProxy: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, proxyTimeout: Int?): String? = withContext(Dispatchers.IO) {
        val useProxy = useProxy && !proxyHost.isNullOrBlank() && proxyPort != null
        try {
            when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val httpEngine = if (useProxy) {
                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                            listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                        } else emptyList()
                        val builder = HttpEngine.Builder(application)
                        try {
                            builder.setProxyOptions(ProxyOptions.fromProxyList(
                                listOf(
                                    android.net.http.Proxy.createHttpProxy(
                                        android.net.http.Proxy.SCHEME_HTTP,
                                        proxyHost,
                                        proxyPort,
                                        xtraModule.cronetExecutor.value,
                                        object : android.net.http.Proxy.HttpConnectCallback {
                                            override fun onBeforeRequest(request: android.net.http.Proxy.HttpConnectCallback.Request) {
                                                request.proceed(proxyHeaders)
                                            }

                                            override fun onResponseReceived(responseHeaders: List<android.util.Pair<String?, String?>?>, statusCode: Int): Int {
                                                return android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED
                                            }
                                        }
                                    )
                                ),
                                ProxyOptions.ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                            ))
                        } catch (e: NoClassDefFoundError) {
                            null
                        }?.build()
                    } else {
                        xtraModule.httpEngine.value!!
                    }
                    if (httpEngine != null) {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout(proxyTimeout?.toLong() ?: CRONET_TIMEOUT)
                            val request = httpEngine.newUrlRequestBuilder(
                                playlistUrl,
                                xtraModule.cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode in 200..299) {
                            response.body.decodeToString()
                        } else null
                    } else {
                        okHttpClient.value.newBuilder().apply {
                            if (proxyTimeout != null) {
                                val proxyTimeout = proxyTimeout.toLong()
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            }
                            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort!!)))
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                                }
                            }
                        }.build().newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                            if (response.isSuccessful) {
                                response.body.string()
                            } else null
                        }
                    }
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val cronetEngine = if (useProxy) {
                        if (CronetProvider.getAllProviders(application).any { it.isEnabled }) {
                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                            } else emptyList()
                            val builder = CronetEngine.Builder(application).apply {
                                val userAgent = "Cronet/" + defaultUserAgent.substringAfter("Cronet/", "").substringBefore(')')
                                setUserAgent(userAgent)
                                @QuicOptions.Experimental
                                setQuicOptions(QuicOptions.builder().setHandshakeUserAgent(userAgent).build())
                            }
                            try {
                                @org.chromium.net.ProxyOptions.Experimental
                                builder.setProxyOptions(org.chromium.net.ProxyOptions(
                                    listOf(
                                        org.chromium.net.Proxy(
                                            org.chromium.net.Proxy.HTTP,
                                            proxyHost,
                                            proxyPort,
                                            xtraModule.cronetExecutor.value,
                                            object : org.chromium.net.Proxy.Callback() {
                                                override fun onBeforeTunnelRequest(request: Request) {
                                                    request.proceed(proxyHeaders)
                                                }

                                                override fun onTunnelHeadersReceived(responseHeaders: List<Map.Entry<String?, String?>?>, statusCode: Int): Boolean {
                                                    return true
                                                }
                                            }
                                        )
                                    )
                                ))
                            } catch (e: UnsupportedOperationException) {
                                null
                            }?.build()
                        } else null
                    } else {
                        xtraModule.cronetEngine.value!!
                    }
                    if (cronetEngine != null) {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout(proxyTimeout?.toLong() ?: CRONET_TIMEOUT)
                            val request = cronetEngine.newUrlRequestBuilder(
                                playlistUrl,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                xtraModule.cronetExecutor.value
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (response.info.httpStatusCode in 200..299) {
                            response.body.decodeToString()
                        } else null
                    } else {
                        okHttpClient.value.newBuilder().apply {
                            if (proxyTimeout != null) {
                                val proxyTimeout = proxyTimeout.toLong()
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            }
                            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort!!)))
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                                }
                            }
                        }.build().newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                            if (response.isSuccessful) {
                                response.body.string()
                            } else null
                        }
                    }
                }
                else -> {
                    val okHttpClient = if (useProxy) {
                        okHttpClient.value.newBuilder().apply {
                            if (proxyTimeout != null) {
                                val proxyTimeout = proxyTimeout.toLong()
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            }
                            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)).build()
                                }
                            }
                        }.build()
                    } else {
                        okHttpClient.value
                    }
                    okHttpClient.newCall(Request.Builder().url(playlistUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.string()
                        } else null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getQualities(playlist: String): List<VideoQuality> = withContext(Dispatchers.IO) {
        val stableVariantIds = Regex("STABLE-VARIANT-ID=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
        val resolutions = Regex("RESOLUTION=(\\d+x\\d+)").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
        val frameRates = Regex("FRAME-RATE=([\\d.]+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toFloatOrNull() }.toList()
        val bitrates = Regex("BANDWIDTH=(\\d+)\\b").findAll(playlist).mapNotNull { it.groups[1]?.value?.toIntOrNull() }.toList()
        val codecs = Regex("CODECS=\"(.+?)\"").findAll(playlist).mapNotNull { it.groups[1]?.value }.toList()
        val urls = Regex("https://.*\\.m3u8").findAll(playlist).map(MatchResult::value).toList()
        val list = stableVariantIds.mapIndexedNotNull { index, variantId ->
            urls.getOrNull(index)?.let { url ->
                if (!variantId.endsWith("-portrait")) {
                    VideoQuality(variantId, resolutions.getOrNull(index)?.substringAfter('x')?.toIntOrNull(), frameRates.getOrNull(index), bitrates.getOrNull(index), codecs.getOrNull(index), url)
                } else null
            }
        }
        list
            .sortedByDescending { it.bitrate }
            .sortedByDescending { it.frameRate }
            .sortedByDescending { it.resolution }
            .toMutableList().apply {
                find { it.name.equals("source", true) }?.let { source ->
                    remove(source)
                    add(0, VideoQuality(VideoQuality.SOURCE_QUALITY, source.resolution, source.frameRate, source.bitrate, source.codecs, source.url))
                }
                find { it.name?.startsWith("audio", true) == true }?.let { audio ->
                    remove(audio)
                    add(VideoQuality(VideoQuality.AUDIO_ONLY_QUALITY, audio.resolution, audio.frameRate, audio.bitrate, audio.codecs, audio.url))
                }
            }
    }

    private suspend fun download(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, channelLogin: String, sourceUrl: String, path: String, networkLibrary: String?) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val liveCheck = max(prefs().getString(C.DOWNLOAD_STREAM_LIVE_CHECK, "2")?.toLongOrNull() ?: 2L, 2L) * 1000L
        val downloadDate = System.currentTimeMillis()
        var startTime = System.currentTimeMillis()
        var lastUrl = downloadProgress.lastSegmentUrl
        var initSegmentUri: String?
        val playlist = when {
            networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                    val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                        sourceUrl,
                        xtraModule.cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                } else {
                    return@withContext
                }
            }
            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                    val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                        sourceUrl,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        xtraModule.cronetExecutor.value
                    ).build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                if (response.info.httpStatusCode in 200..299) {
                    response.body.inputStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                } else {
                    return@withContext
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                    if (response.isSuccessful) {
                        response.body.byteStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
            }
        }
        val firstUrls = if (playlist.segments.isNotEmpty()) {
            val urls = playlist.segments.takeLastWhile { it.uri != lastUrl }
            urls.lastOrNull()?.let { lastUrl = it.uri }
            initSegmentUri = playlist.initSegmentUri
            urls.map { it.uri }
        } else {
            return@withContext
        }
        val videoFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            val fileUri = offlineVideo.url!!
            if (isShared) {
                contentResolver.openFileDescriptor(fileUri.toUri(), "rw")!!.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        output.channel.truncate(downloadProgress.bytes)
                    }
                }
            } else {
                FileOutputStream(fileUri).use { output ->
                    output.channel.truncate(downloadProgress.bytes)
                }
            }
            fileUri
        } else {
            val fileName = "${offlineVideo.channelLogin ?: ""}${offlineVideo.quality ?: ""}${downloadDate}.${firstUrls.first().substringAfterLast(".").substringBefore("?")}"
            val fileUri = if (isShared) {
                val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
                val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
                val fileUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + fileName
                try {
                    contentResolver.openOutputStream(fileUri.toUri())!!.close()
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, directoryUri, "", fileName)
                }
                fileUri
            } else {
                "$path${File.separator}$fileName"
            }
            val initSegmentBytes = initSegmentUri?.let { url ->
                when {
                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                url,
                                xtraModule.cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                        } else {
                            FileOutputStream(fileUri, true)
                        }.use {
                            it.write(response.body)
                        }
                        response.body.size.toLong()
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                url,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                xtraModule.cronetExecutor.value
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        if (isShared) {
                            contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                        } else {
                            FileOutputStream(fileUri, true)
                        }.use {
                            it.write(response.body)
                        }
                        response.body.size.toLong()
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            if (isShared) {
                                contentResolver.openOutputStream(fileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(fileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                            response.body.contentLength()
                        }
                    }
                }
            }
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = fileUri
                initSegmentBytes?.let {
                    bytes += it
                    downloadProgress.bytes += it
                }
            })
            if (offlineVideo.name.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    updateStreamInfo(offlineVideo, channelLogin, networkLibrary)
                }
            }
            fileUri
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val firstJobs = firstUrls.mapIndexed { index, url ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                when {
                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                url,
                                xtraModule.cronetExecutor.value,
                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        val mutex = Mutex()
                        if (count.value != index) {
                            mutex.lock()
                            mutexMap[index] = mutex
                        }
                        mutex.withLock {
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(videoFileUri, true)
                            }.use {
                                it.write(response.body)
                            }
                            downloadProgress.bytes += response.body.size
                            downloadProgress.lastSegmentUrl += lastUrl
                        }
                    }
                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                        val response = suspendCancellableCoroutine { continuation ->
                            val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                url,
                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                xtraModule.cronetExecutor.value
                            ).build()
                            timeout.start(request, continuation)
                            request.start()
                            continuation.invokeOnCancellation {
                                request.cancel()
                                timeout.stop()
                            }
                        }
                        val mutex = Mutex()
                        if (count.value != index) {
                            mutex.lock()
                            mutexMap[index] = mutex
                        }
                        mutex.withLock {
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                            } else {
                                FileOutputStream(videoFileUri, true)
                            }.use {
                                it.write(response.body)
                            }
                            downloadProgress.bytes += response.body.size
                            downloadProgress.lastSegmentUrl += lastUrl
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            val mutex = Mutex()
                            if (count.value != index) {
                                mutex.lock()
                                mutexMap[index] = mutex
                            }
                            mutex.withLock {
                                if (isShared) {
                                    contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                } else {
                                    FileOutputStream(videoFileUri)
                                }.use { outputStream ->
                                    response.body.byteStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                    downloadProgress.bytes += response.body.contentLength()
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                        }
                    }
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - downloadProgress.lastSaved >= 5000L) {
                    downloadProgress.lastSaved = currentTime
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        bytes = downloadProgress.bytes
                        lastSegmentUrl = downloadProgress.lastSegmentUrl
                    })
                }
                count.update { it + 1 }
                mutexMap.remove(count.value)?.unlock()
            }.also {
                it.invokeOnCompletion {
                    requestSemaphore.release()
                }
            }
        }
        firstJobs.joinAll()
        while (true) {
            val playlist = when {
                networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                        val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                            sourceUrl,
                            xtraModule.cronetExecutor.value,
                            NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
                networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                    val response = suspendCancellableCoroutine { continuation ->
                        val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                        val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                            sourceUrl,
                            NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                            xtraModule.cronetExecutor.value
                        ).build()
                        timeout.start(request, continuation)
                        request.start()
                        continuation.invokeOnCancellation {
                            request.cancel()
                            timeout.stop()
                        }
                    }
                    if (response.info.httpStatusCode in 200..299) {
                        response.body.inputStream().use {
                            PlaylistUtils.parseMediaPlaylist(it)
                        }
                    } else {
                        return@withContext
                    }
                }
                else -> {
                    okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                        if (response.isSuccessful) {
                            response.body.byteStream().use {
                                PlaylistUtils.parseMediaPlaylist(it)
                            }
                        } else {
                            return@withContext
                        }
                    }
                }
            }
            if (playlist.segments.isNotEmpty()) {
                val urls = playlist.segments.map { it.uri }.takeLastWhile { it != lastUrl }
                urls.lastOrNull()?.let { lastUrl = it }
                val mutexMap = mutableMapOf<Int, Mutex>()
                val count = MutableStateFlow(0)
                val jobs = urls.mapIndexed { index, url ->
                    requestSemaphore.acquire()
                    launch(Dispatchers.IO) {
                        when {
                            networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                val response = suspendCancellableCoroutine { continuation ->
                                    val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                                    val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                        url,
                                        xtraModule.cronetExecutor.value,
                                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                    ).build()
                                    timeout.start(request, continuation)
                                    request.start()
                                    continuation.invokeOnCancellation {
                                        request.cancel()
                                        timeout.stop()
                                    }
                                }
                                val mutex = Mutex()
                                if (count.value != index) {
                                    mutex.lock()
                                    mutexMap[index] = mutex
                                }
                                mutex.withLock {
                                    if (isShared) {
                                        contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                    } else {
                                        FileOutputStream(videoFileUri, true)
                                    }.use {
                                        it.write(response.body)
                                    }
                                    downloadProgress.bytes += response.body.size
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                            networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                val response = suspendCancellableCoroutine { continuation ->
                                    val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                                    val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                        url,
                                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                        xtraModule.cronetExecutor.value
                                    ).build()
                                    timeout.start(request, continuation)
                                    request.start()
                                    continuation.invokeOnCancellation {
                                        request.cancel()
                                        timeout.stop()
                                    }
                                }
                                val mutex = Mutex()
                                if (count.value != index) {
                                    mutex.lock()
                                    mutexMap[index] = mutex
                                }
                                mutex.withLock {
                                    if (isShared) {
                                        contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                    } else {
                                        FileOutputStream(videoFileUri, true)
                                    }.use {
                                        it.write(response.body)
                                    }
                                    downloadProgress.bytes += response.body.size
                                    downloadProgress.lastSegmentUrl += lastUrl
                                }
                            }
                            else -> {
                                okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                    val mutex = Mutex()
                                    if (count.value != index) {
                                        mutex.lock()
                                        mutexMap[index] = mutex
                                    }
                                    mutex.withLock {
                                        if (isShared) {
                                            contentResolver.openOutputStream(videoFileUri.toUri(), "wa")!!
                                        } else {
                                            FileOutputStream(videoFileUri)
                                        }.use { outputStream ->
                                            response.body.byteStream().use { inputStream ->
                                                inputStream.copyTo(outputStream)
                                            }
                                            downloadProgress.bytes += response.body.contentLength()
                                            downloadProgress.lastSegmentUrl += lastUrl
                                        }
                                    }
                                }
                            }
                        }
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - downloadProgress.lastSaved >= 5000L) {
                            downloadProgress.lastSaved = currentTime
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                bytes = downloadProgress.bytes
                                lastSegmentUrl = downloadProgress.lastSegmentUrl
                            })
                        }
                        count.update { it + 1 }
                        mutexMap.remove(count.value)?.unlock()
                    }.also {
                        it.invokeOnCompletion {
                            requestSemaphore.release()
                        }
                    }
                }
                jobs.joinAll()
                if (playlist.end) {
                    return@withContext
                }
            } else {
                return@withContext
            }
            val timeTaken = System.currentTimeMillis() - startTime
            if ((timeTaken) < liveCheck) {
                delay((liveCheck - timeTaken).milliseconds)
            }
            startTime = System.currentTimeMillis()
        }
    }

    private suspend fun updateStreamInfo(offlineVideo: OfflineVideo, channelLogin: String, networkLibrary: String?) = withContext(Dispatchers.IO) {
        var attempt = 1
        while (attempt <= 10) {
            delay(10.seconds)
            val channelId = offlineVideo.channelId
            val stream = try {
                xtraModule.graphQLRepository.loadQueryUsersStream(
                    networkLibrary = networkLibrary,
                    headers = TwitchApiHelper.getGQLHeaders(this@StreamDownloadService),
                    ids = channelId?.let { listOf(it) },
                    logins = if (channelId.isNullOrBlank()) listOf(channelLogin) else null,
                ).data!!.users?.firstOrNull()?.let {
                    Stream(
                        id = it.stream?.id,
                        channelId = it.id,
                        channelLogin = it.login,
                        channelName = it.displayName,
                        channelImageURL = it.profileImageURL,
                        gameId = it.stream?.game?.id,
                        gameSlug = it.stream?.game?.slug,
                        gameName = it.stream?.game?.displayName,
                        title = it.stream?.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = it.stream?.previewImageURL,
                        createdAt = it.stream?.createdAt?.toString(),
                        viewerCount = it.stream?.viewersCount,
                        tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            } catch (e: Exception) {
                val helixHeaders = TwitchApiHelper.getHelixHeaders(this@StreamDownloadService)
                if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) throw Exception()
                try {
                    xtraModule.helixRepository.getStreams(
                        networkLibrary = networkLibrary,
                        headers = helixHeaders,
                        ids = channelId?.let { listOf(it) },
                        logins = if (channelId.isNullOrBlank()) listOf(channelLogin) else null
                    ).data.firstOrNull()?.let {
                        Stream(
                            id = it.id,
                            channelId = it.channelId,
                            channelLogin = it.channelLogin,
                            channelName = it.channelName,
                            gameId = it.gameId,
                            gameName = it.gameName,
                            title = it.title,
                            thumbnailURL = it.thumbnailURL,
                            createdAt = it.startedAt,
                            viewerCount = it.viewerCount,
                            tags = it.tags,
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (stream != null) {
                val downloadedThumbnail = stream.id.takeIf { !it.isNullOrBlank() }?.let { id ->
                    stream.thumbnail.takeIf { !it.isNullOrBlank() }?.let { url ->
                        val filesDir = filesDir.path
                        File(filesDir, "thumbnails").mkdir()
                        val filePath = filesDir + File.separator + "thumbnails" + File.separator + id
                        launch(Dispatchers.IO) {
                            try {
                                when {
                                    networkLibrary == C.HTTP_ENGINE && xtraModule.httpEngine.value != null -> @SuppressLint("NewApi") {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.HttpEngineTimeout(CRONET_TIMEOUT)
                                            val request = xtraModule.httpEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                xtraModule.cronetExecutor.value,
                                                NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(filePath).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    networkLibrary == C.CRONET && xtraModule.cronetEngine.value != null -> {
                                        val response = suspendCancellableCoroutine { continuation ->
                                            val timeout = NetworkUtils.CronetTimeout(CRONET_TIMEOUT)
                                            val request = xtraModule.cronetEngine.value!!.newUrlRequestBuilder(
                                                url,
                                                NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                                xtraModule.cronetExecutor.value
                                            ).build()
                                            timeout.start(request, continuation)
                                            request.start()
                                            continuation.invokeOnCancellation {
                                                request.cancel()
                                                timeout.stop()
                                            }
                                        }
                                        if (response.info.httpStatusCode in 200..299) {
                                            FileOutputStream(filePath).use {
                                                it.write(response.body)
                                            }
                                        }
                                    }
                                    else -> {
                                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                            if (response.isSuccessful) {
                                                FileOutputStream(filePath).use { outputStream ->
                                                    response.body.byteStream().use { inputStream ->
                                                        inputStream.copyTo(outputStream)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {

                            }
                        }
                        filePath
                    }
                }
                xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                    name = stream.title
                    thumbnail = downloadedThumbnail
                    gameId = stream.gameId
                    gameSlug = stream.gameSlug
                    gameName = stream.gameName
                    uploadDate = stream.createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } }
                })
                break
            }
            attempt += 1
        }
    }

    private fun sendNotification(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, getString(R.string.notification_downloads_channel_id))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(ContextCompat.getString(this@StreamDownloadService, if (downloadProgress.isLive) {
                R.string.downloading
            } else {
                R.string.download_waiting_for_stream
            }))
            setContentText(offlineVideo.channelName)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setGroup(GROUP_KEY)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setContentIntent(
                PendingIntent.getActivity(
                    this@StreamDownloadService,
                    offlineVideo.id,
                    Intent(this@StreamDownloadService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this@StreamDownloadService, android.R.drawable.ic_delete),
                    ContextCompat.getString(this@StreamDownloadService, R.string.stop),
                    PendingIntent.getService(
                        this@StreamDownloadService,
                        REQUEST_CODE_STOP,
                        Intent(this@StreamDownloadService, StreamDownloadService::class.java).apply {
                            action = INTENT_STOP
                            putExtra(KEY_VIDEO_ID, offlineVideo.id)
                        },
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
        }.build()
        if (downloadProgress == activeDownloads.firstOrNull()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(offlineVideo.id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(offlineVideo.id, notification)
            }
        } else {
            notificationManager?.notify(offlineVideo.id, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            INTENT_STOP, INTENT_CANCEL -> {
                val videoId = intent.getIntExtra(KEY_VIDEO_ID, 0)
                downloadJobs.find { it.id == videoId }?.job?.cancel()
                val offlineVideo = offlineVideos.find { it.id == videoId }
                val downloadProgress = activeDownloads.find { it.id == videoId }
                if (offlineVideo != null && downloadProgress != null) {
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    if (intent.action == INTENT_STOP) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_PENDING
                                bytes = downloadProgress.bytes
                                lastSegmentUrl = downloadProgress.lastSegmentUrl
                            })
                        }
                    }
                }
                if (intent.action == INTENT_STOP) {
                    val nextOfflineVideo = offlineVideos.firstOrNull()
                    val nextDownload = activeDownloads.firstOrNull()
                    if (nextOfflineVideo != null && nextDownload != null) {
                        sendNotification(nextOfflineVideo, nextDownload)
                        notificationManager?.cancel(videoId)
                    } else {
                        listener?.unbind()
                        stopSelf()
                    }
                }
            }
            INTENT_START -> start(intent.getIntExtra(KEY_VIDEO_ID, 0))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@StreamDownloadService
    }

    override fun onDestroy() {
        super.onDestroy()
        activeDownloads.forEach {
            notificationManager?.cancel(it.id)
        }
    }

    companion object {
        private const val CRONET_TIMEOUT = 300_000L
        private const val GROUP_KEY = "com.github.andreyasadchy.xtra.DOWNLOADS"

        private const val REQUEST_CODE_STOP = 0

        const val KEY_VIDEO_ID = "videoId"

        const val INTENT_STOP = "com.github.andreyasadchy.xtra.STOP"
        const val INTENT_CANCEL = "com.github.andreyasadchy.xtra.CANCEL"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_VIDEO_DOWNLOAD_SERVICE"
    }
}
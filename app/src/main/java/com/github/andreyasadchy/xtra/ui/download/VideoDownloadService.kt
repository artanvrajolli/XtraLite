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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.ui.DownloadProgress
import com.github.andreyasadchy.xtra.model.ui.OfflineVideo
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import com.github.andreyasadchy.xtra.util.m3u8.MediaPlaylist
import com.github.andreyasadchy.xtra.util.m3u8.PlaylistUtils
import com.github.andreyasadchy.xtra.util.m3u8.Segment
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class VideoDownloadService : LifecycleService() {

    lateinit var xtraModule: XtraModule
    private val okHttpClient = lazy {
        xtraModule.okHttpClient.value.newBuilder().apply {
            connectTimeout(5, TimeUnit.MINUTES)
            writeTimeout(5, TimeUnit.MINUTES)
            readTimeout(5, TimeUnit.MINUTES)
        }.build()
    }

    private var notificationManager: NotificationManager? = null
    private lateinit var downloadSemaphore: Semaphore
    private val downloadJobs = mutableMapOf<Int, Job>()
    private val offlineVideos = mutableListOf<OfflineVideo>()
    val activeDownloads = mutableListOf<DownloadProgress>()
    var listener: Listener? = null

    interface Listener {
        fun update(downloadProgress: DownloadProgress)
        fun unbind()
    }

    override fun onCreate() {
        super.onCreate()
        xtraModule = (application as XtraApp).xtraModule
        downloadSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_LIMIT, 2))
    }

    private fun start(videoId: Int) {
        if (activeDownloads.find { it.id == videoId } == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val offlineVideo = xtraModule.offlineVideosRepository.getById(videoId)
                if (offlineVideo != null) {
                    val downloadProgress = DownloadProgress(
                        id = videoId,
                        progress = offlineVideo.progress,
                        maxProgress = offlineVideo.maxProgress,
                        bytes = offlineVideo.bytes,
                    )
                    offlineVideos.add(offlineVideo)
                    activeDownloads.add(downloadProgress)
                    if (downloadSemaphore.availablePermits <= 0) {
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            status = OfflineVideo.STATUS_QUEUED
                        })
                    }
                    downloadSemaphore.withPermit {
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            status = OfflineVideo.STATUS_DOWNLOADING
                        })
                        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        val channelId = getString(R.string.notification_downloads_channel_id)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager?.getNotificationChannel(channelId) == null) {
                            notificationManager?.createNotificationChannel(
                                NotificationChannel(
                                    channelId,
                                    ContextCompat.getString(this@VideoDownloadService, R.string.notification_downloads_channel_title),
                                    NotificationManager.IMPORTANCE_DEFAULT
                                ).apply {
                                    setSound(null, null)
                                }
                            )
                        }
                        sendNotification(offlineVideo, downloadProgress)
                        try {
                            val sourceUrl = offlineVideo.sourceUrl!!
                            if (sourceUrl.endsWith(".m3u8")) {
                                downloadVideo(offlineVideo, downloadProgress, sourceUrl)
                            } else {
                                downloadClip(offlineVideo, downloadProgress, sourceUrl)
                            }
                        } catch (e: CancellationException) {
                            ensureActive()
                        } catch (e: Exception) {
                            Log.e("VideoDownloadService", "Download failed", e)
                        }
                        offlineVideos.remove(offlineVideo)
                        activeDownloads.remove(downloadProgress)
                        val done = downloadProgress.progress >= downloadProgress.maxProgress
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
                            progress = downloadProgress.progress
                            maxProgress = downloadProgress.maxProgress
                            bytes = downloadProgress.bytes
                        })
                        if (done) {
                            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                Notification.Builder(this@VideoDownloadService, getString(R.string.notification_downloads_channel_id))
                            } else {
                                @Suppress("DEPRECATION")
                                Notification.Builder(this@VideoDownloadService)
                            }.apply {
                                setContentTitle(ContextCompat.getString(this@VideoDownloadService, R.string.downloaded))
                                setContentText(offlineVideo.name)
                                setSmallIcon(android.R.drawable.stat_sys_download_done)
                                setGroup(GROUP_KEY)
                                setAutoCancel(true)
                                setContentIntent(
                                    PendingIntent.getActivity(
                                        this@VideoDownloadService,
                                        -offlineVideo.id,
                                        Intent(this@VideoDownloadService, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            action = MainActivity.INTENT_OPEN_DOWNLOADED_VIDEO
                                            putExtra(MainActivity.KEY_VIDEO, offlineVideo)
                                        },
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                    )
                                )
                            }.build()
                            notificationManager?.notify(-offlineVideo.id, notification)
                        }
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
            }.also {
                it.invokeOnCompletion {
                    downloadJobs.remove(videoId)
                }
                downloadJobs[videoId] = it
            }
        }
    }

    private suspend fun downloadVideo(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, sourceUrl: String) = withContext(Dispatchers.IO) {
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val path = offlineVideo.downloadPath!!
        val from = offlineVideo.fromTime!!
        val to = offlineVideo.toTime!!
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
                response.body.inputStream().use {
                    PlaylistUtils.parseMediaPlaylist(it)
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
                response.body.inputStream().use {
                    PlaylistUtils.parseMediaPlaylist(it)
                }
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                    response.body.byteStream().use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                }
            }
        }
        val segments = mutableListOf<Segment>()
        var totalDuration = 0L
        var downloadDuration = 0L
        var startPosition = -1L
        for (segment in playlist.segments) {
            val startTime = totalDuration
            val duration = (segment.duration * 1000f).toLong()
            val endTime = startTime + duration
            if (endTime <= from) {
                totalDuration = endTime
            } else {
                if (startTime < to) {
                    segments.add(segment.copy(uri = segment.uri.replace("-unmuted", "-muted")))
                    totalDuration = endTime
                    downloadDuration += duration
                    if (startPosition == -1L) {
                        startPosition = startTime
                    }
                } else {
                    break
                }
            }
        }
        if (offlineVideo.duration == null) {
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                duration = downloadDuration
                sourceStartPosition = startPosition
                maxProgress = segments.size
            })
            downloadProgress.maxProgress = offlineVideo.maxProgress
        }
        val urlPath = sourceUrl.substringBeforeLast('/') + "/"
        if (offlineVideo.playlistToFile) {
            downloadPlaylistToFile(offlineVideo, downloadProgress, networkLibrary, urlPath, path, playlist, segments)
        } else {
            downloadPlaylist(offlineVideo, downloadProgress, networkLibrary, urlPath, path, playlist, segments)
        }
    }

    private suspend fun downloadPlaylistToFile(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, networkLibrary: String?, urlPath: String, path: String, playlist: MediaPlaylist, segments: List<Segment>) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
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
            val fileName = "${offlineVideo.videoId ?: ""}${offlineVideo.quality ?: ""}${offlineVideo.downloadDate}.${segments.first().uri.substringAfterLast(".")}"
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
            val initSegmentBytes = if (playlist.initSegmentUri != null) {
                val url = urlPath + playlist.initSegmentUri
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
            } else null
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = fileUri
                initSegmentBytes?.let {
                    bytes += it
                    downloadProgress.bytes += it
                }
            })
            fileUri
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val jobs = segments.drop(downloadProgress.progress).mapIndexed { index, segment ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                val url = urlPath + segment.uri
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
                            downloadProgress.progress += 1
                            listener?.update(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
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
                            downloadProgress.progress += 1
                            listener?.update(downloadProgress)
                            sendNotification(offlineVideo, downloadProgress)
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
                                    downloadProgress.progress += 1
                                    listener?.update(downloadProgress)
                                    sendNotification(offlineVideo, downloadProgress)
                                }
                            }
                        }
                    }
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - downloadProgress.lastSaved >= 5000L) {
                    downloadProgress.lastSaved = currentTime
                    xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                        progress = downloadProgress.progress
                        maxProgress = downloadProgress.maxProgress
                        bytes = downloadProgress.bytes
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
    }

    private suspend fun downloadPlaylist(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, networkLibrary: String?, urlPath: String, path: String, playlist: MediaPlaylist, segments: List<Segment>) = withContext(Dispatchers.IO) {
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val videoDirectoryName = if (!offlineVideo.videoId.isNullOrBlank()) {
            "${offlineVideo.videoId}${offlineVideo.quality ?: ""}"
        } else {
            "${offlineVideo.downloadDate}"
        }
        val videoDirectoryUri = if (isShared) {
            val documentId = DocumentsContract.getTreeDocumentId(path.toUri())
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(path.toUri(), documentId)
            val videoDirectoryUri = directoryUri.toString() + (if (!directoryUri.toString().endsWith("%3A")) "%2F" else "") + videoDirectoryName
            try {
                contentResolver.openOutputStream(videoDirectoryUri.toUri())!!.close()
            } catch (e: Exception) {
                if (e is IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, directoryUri, DocumentsContract.Document.MIME_TYPE_DIR, videoDirectoryName)
                }
            }
            videoDirectoryUri
        } else {
            val videoDirectoryUri = "$path${File.separator}$videoDirectoryName${File.separator}"
            File(videoDirectoryUri).mkdir()
            videoDirectoryUri
        }
        val playlistFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            offlineVideo.url!!
        } else {
            val playlistFileUri = if (isShared) {
                val fileName = "${offlineVideo.downloadDate}.m3u8"
                val playlistFileUri = "$videoDirectoryUri%2F$fileName"
                try {
                    contentResolver.openOutputStream(playlistFileUri.toUri())!!
                } catch (e: IllegalArgumentException) {
                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", fileName)
                    contentResolver.openOutputStream(playlistFileUri.toUri())!!
                }.use {
                    PlaylistUtils.writeMediaPlaylist(playlist.copy(
                        initSegmentUri = playlist.initSegmentUri?.let { uri -> "$videoDirectoryUri%2F$uri" },
                        segments = segments.map { segment -> segment.copy(uri = videoDirectoryUri + "%2F" + segment.uri) }
                    ), it)
                }
                playlistFileUri
            } else {
                val playlistFileUri = "$videoDirectoryUri${offlineVideo.downloadDate}.m3u8"
                FileOutputStream(playlistFileUri).use {
                    PlaylistUtils.writeMediaPlaylist(playlist.copy(segments = segments), it)
                }
                playlistFileUri
            }
            if (playlist.initSegmentUri != null) {
                val initSegmentFileUri = if (isShared) {
                    videoDirectoryUri + "%2F" + playlist.initSegmentUri
                } else {
                    videoDirectoryUri + playlist.initSegmentUri
                }
                val url = urlPath + playlist.initSegmentUri
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
                            try {
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            }
                        } else {
                            FileOutputStream(initSegmentFileUri)
                        }.use {
                            it.write(response.body)
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
                        if (isShared) {
                            try {
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            } catch (e: IllegalArgumentException) {
                                DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                            }
                        } else {
                            FileOutputStream(initSegmentFileUri)
                        }.use {
                            it.write(response.body)
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                            if (isShared) {
                                try {
                                    contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", playlist.initSegmentUri)
                                    contentResolver.openOutputStream(initSegmentFileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(initSegmentFileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                    }
                }
            }
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = playlistFileUri
            })
            playlistFileUri
        }
        val downloadedSegments = mutableListOf<String>()
        if (isShared) {
            val playlists = xtraModule.offlineVideosRepository.getPlaylists().mapNotNull { video ->
                video.url?.takeIf {
                    it.toUri().scheme == ContentResolver.SCHEME_CONTENT
                            && it.substringBeforeLast("%2F") == videoDirectoryUri
                            && it != playlistFileUri
                }
            }
            playlists.forEach { uri ->
                try {
                    val p = contentResolver.openInputStream(uri.toUri())!!.use {
                        PlaylistUtils.parseMediaPlaylist(it)
                    }
                    p.segments.forEach { downloadedSegments.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
                } catch (e: Exception) {

                }
            }
        } else {
            val playlists = File(videoDirectoryUri).listFiles { it.extension == "m3u8" && it.path != playlistFileUri }
            playlists?.forEach { file ->
                val p = PlaylistUtils.parseMediaPlaylist(file.inputStream())
                p.segments.forEach { downloadedSegments.add(it.uri.substringAfterLast("%2F").substringAfterLast("/")) }
            }
        }
        val requestSemaphore = Semaphore(prefs().getInt(C.DOWNLOAD_CONCURRENT_LIMIT, 10))
        val mutexMap = mutableMapOf<Int, Mutex>()
        val count = MutableStateFlow(0)
        downloadProgress.lastSaved = System.currentTimeMillis()
        val jobs = segments.drop(downloadProgress.progress).mapIndexed { index, segment ->
            requestSemaphore.acquire()
            launch(Dispatchers.IO) {
                val fileUri = if (isShared) {
                    videoDirectoryUri + "%2F" + segment.uri
                } else {
                    videoDirectoryUri + segment.uri
                }
                val exists = if (isShared) {
                    try {
                        contentResolver.openOutputStream(fileUri.toUri())!!.close()
                        true
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                } else {
                    File(fileUri).exists()
                }
                if (!exists || !downloadedSegments.contains(segment.uri)) {
                    val url = urlPath + segment.uri
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
                                try {
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(fileUri)
                            }.use {
                                it.write(response.body)
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
                            if (isShared) {
                                try {
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                } catch (e: IllegalArgumentException) {
                                    DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                    contentResolver.openOutputStream(fileUri.toUri())!!
                                }
                            } else {
                                FileOutputStream(fileUri)
                            }.use {
                                it.write(response.body)
                            }
                        }
                        else -> {
                            okHttpClient.value.newCall(Request.Builder().url(url).build()).executeAsync().use { response ->
                                if (isShared) {
                                    try {
                                        contentResolver.openOutputStream(fileUri.toUri())!!
                                    } catch (e: IllegalArgumentException) {
                                        DocumentsContract.createDocument(contentResolver, videoDirectoryUri.toUri(), "", segment.uri)
                                        contentResolver.openOutputStream(fileUri.toUri())!!
                                    }
                                } else {
                                    FileOutputStream(fileUri)
                                }.use { outputStream ->
                                    response.body.byteStream().use { inputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }
                            }
                        }
                    }
                }
                val mutex = Mutex()
                if (count.value != index) {
                    mutex.lock()
                    mutexMap[index] = mutex
                }
                mutex.withLock {
                    downloadProgress.progress += 1
                    listener?.update(downloadProgress)
                    sendNotification(offlineVideo, downloadProgress)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - downloadProgress.lastSaved >= 5000L) {
                        downloadProgress.lastSaved = currentTime
                        xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                            progress = downloadProgress.progress
                            maxProgress = downloadProgress.maxProgress
                            bytes = downloadProgress.bytes
                        })
                    }
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
    }

    private suspend fun downloadClip(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, sourceUrl: String) = withContext(Dispatchers.IO) {
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val path = offlineVideo.downloadPath!!
        val isShared = path.toUri().scheme == ContentResolver.SCHEME_CONTENT
        val videoFileUri = if (!offlineVideo.url.isNullOrBlank()) {
            offlineVideo.url!!
        } else {
            val fileName = if (!offlineVideo.clipId.isNullOrBlank()) {
                "${offlineVideo.clipId}${offlineVideo.quality ?: ""}.mp4"
            } else {
                "${offlineVideo.downloadDate}.mp4"
            }
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
            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                url = fileUri
            })
            fileUri
        }
        downloadProgress.lastSaved = System.currentTimeMillis()
        val job = launch(Dispatchers.IO) {
            if (downloadProgress.progress < downloadProgress.maxProgress) {
                when {
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
                        if (isShared) {
                            contentResolver.openOutputStream(videoFileUri.toUri())!!
                        } else {
                            FileOutputStream(videoFileUri)
                        }.use {
                            it.write(response.body)
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
                        if (isShared) {
                            contentResolver.openOutputStream(videoFileUri.toUri())!!
                        } else {
                            FileOutputStream(videoFileUri)
                        }.use {
                            it.write(response.body)
                        }
                    }
                    else -> {
                        okHttpClient.value.newCall(Request.Builder().url(sourceUrl).build()).executeAsync().use { response ->
                            if (isShared) {
                                contentResolver.openOutputStream(videoFileUri.toUri())!!
                            } else {
                                FileOutputStream(videoFileUri)
                            }.use { outputStream ->
                                response.body.byteStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }
                    }
                }
                downloadProgress.progress = downloadProgress.maxProgress
                listener?.update(downloadProgress)
                sendNotification(offlineVideo, downloadProgress)
            }
        }
        job.join()
    }

    private fun sendNotification(offlineVideo: OfflineVideo, downloadProgress: DownloadProgress, paused: Boolean = false) {
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, getString(R.string.notification_downloads_channel_id))
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle(ContextCompat.getString(this@VideoDownloadService, R.string.downloading))
            setContentText(offlineVideo.name)
            setSmallIcon(android.R.drawable.stat_sys_download)
            setGroup(GROUP_KEY)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(downloadProgress.maxProgress, downloadProgress.progress, false)
            setContentIntent(
                PendingIntent.getActivity(
                    this@VideoDownloadService,
                    offlineVideo.id,
                    Intent(this@VideoDownloadService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = MainActivity.INTENT_OPEN_DOWNLOADS_TAB
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            if (paused) {
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@VideoDownloadService, R.drawable.baseline_play_arrow_black_48),
                        ContextCompat.getString(this@VideoDownloadService, R.string.resume),
                        PendingIntent.getService(
                            this@VideoDownloadService,
                            REQUEST_CODE_RESUME,
                            Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                action = INTENT_RESUME
                                putExtra(KEY_VIDEO_ID, offlineVideo.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    ).build()
                )
            } else {
                addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(this@VideoDownloadService, R.drawable.baseline_pause_black_48),
                        ContextCompat.getString(this@VideoDownloadService, R.string.pause),
                        PendingIntent.getService(
                            this@VideoDownloadService,
                            REQUEST_CODE_PAUSE,
                            Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
                                action = INTENT_PAUSE
                                putExtra(KEY_VIDEO_ID, offlineVideo.id)
                            },
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    ).build()
                )
            }
            addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this@VideoDownloadService, android.R.drawable.ic_delete),
                    ContextCompat.getString(this@VideoDownloadService, R.string.stop),
                    PendingIntent.getService(
                        this@VideoDownloadService,
                        REQUEST_CODE_STOP,
                        Intent(this@VideoDownloadService, VideoDownloadService::class.java).apply {
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
            INTENT_PAUSE, INTENT_STOP, INTENT_CANCEL -> {
                val videoId = intent.getIntExtra(KEY_VIDEO_ID, 0)
                downloadJobs[videoId]?.cancel()
                val offlineVideo = offlineVideos.find { it.id == videoId }
                val downloadProgress = activeDownloads.find { it.id == videoId }
                if (offlineVideo != null && downloadProgress != null) {
                    offlineVideos.remove(offlineVideo)
                    activeDownloads.remove(downloadProgress)
                    if (intent.action != INTENT_CANCEL) {
                        if (intent.action == INTENT_PAUSE) {
                            sendNotification(offlineVideo, downloadProgress, paused = true)
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            xtraModule.offlineVideosRepository.update(offlineVideo.apply {
                                status = OfflineVideo.STATUS_PENDING
                                progress = downloadProgress.progress
                                maxProgress = downloadProgress.maxProgress
                                bytes = downloadProgress.bytes
                            })
                        }
                    }
                }
                if (intent.action != INTENT_PAUSE) {
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
            INTENT_START, INTENT_RESUME -> start(intent.getIntExtra(KEY_VIDEO_ID, 0))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return ServiceBinder()
    }

    inner class ServiceBinder : Binder() {
        fun getService() = this@VideoDownloadService
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

        private const val REQUEST_CODE_PAUSE = 0
        private const val REQUEST_CODE_RESUME = 1
        private const val REQUEST_CODE_STOP = 2

        const val KEY_VIDEO_ID = "videoId"

        private const val INTENT_PAUSE = "com.github.andreyasadchy.xtra.PAUSE"
        private const val INTENT_RESUME = "com.github.andreyasadchy.xtra.RESUME"
        const val INTENT_STOP = "com.github.andreyasadchy.xtra.STOP"
        const val INTENT_CANCEL = "com.github.andreyasadchy.xtra.CANCEL"
        const val INTENT_START = "com.github.andreyasadchy.xtra.START_VIDEO_DOWNLOAD_SERVICE"
    }
}
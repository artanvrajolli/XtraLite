package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Relays Twitch HLS playlists to a Cast receiver over the local network.
 *
 * Twitch only sends CORS headers for its own sites on multivariant playlists (live and VOD) and on
 * VOD playlists and segments, so the Default Media Receiver can't load them directly. This server
 * fetches them on the phone, adds CORS headers, rewrites URIs inside playlists to point back here
 * and drops variants most Cast devices can't play (HEVC, AV1, audio only). Live segments already
 * allow any origin, so the receiver fetches those from Twitch directly.
 *
 * Requests must carry a random per-run secret and may only fetch hosts the relay has seen in a URL
 * it was given or in a playlist it served, so it can't be used as an open proxy on the network.
 */
object CastProxyServer {

    private const val TAG = "CastProxyServer"

    private var relay: CastRelay? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null
    private var castContext: CastContext? = null

    /**
     * Returns a URL on this device that serves [url] to the receiver, starting the relay if
     * needed, or null if the device has no local network address. Must be called on the main thread.
     */
    @Synchronized
    fun wrap(context: Context, castContext: CastContext, okHttpClient: OkHttpClient, url: String): String? {
        val address = getLocalAddress() ?: return null
        val relay = relay ?: try {
            CastRelay(okHttpClient).also {
                it.start()
                relay = it
                onStarted(context, castContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            stop()
            return null
        }
        return relay.wrap(url)?.let { "http://${address.hostAddress}:${relay.port}$it" }
    }

    @Synchronized
    fun stop() {
        sessionListener?.let { castContext?.sessionManager?.removeSessionManagerListener(it, CastSession::class.java) }
        sessionListener = null
        castContext = null
        relay?.stop()
        relay = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun onStarted(context: Context, castContext: CastContext) {
        wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        this.castContext = castContext
        sessionListener = object : SessionManagerListener<CastSession> {
            override fun onSessionEnded(session: CastSession, error: Int) = stop()
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionStarted(session: CastSession, sessionId: String) {}
            override fun onSessionStartFailed(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }.also { castContext.sessionManager.addSessionManagerListener(it, CastSession::class.java) }
    }

    private fun getLocalAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                ?.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * HTTP server behind [CastProxyServer]. Plain JVM code so it can be tested off-device.
 */
class CastRelay(okHttpClient: OkHttpClient) {

    private val directSegmentHost = Regex(".*\\.hls\\.ttvnw\\.net$")
    private val unsupportedCodecs = listOf("hvc1", "hev1", "av01")
    private val uriAttribute = Regex("URI=\"([^\"]*)\"")
    private val codecsAttribute = Regex("CODECS=\"([^\"]*)\"")

    private val client = okHttpClient.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
        readTimeout(30, TimeUnit.SECONDS)
    }.build()
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val allowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        val socket = ServerSocket(0)
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val connection = socket.accept()
                    executor.execute { handle(connection) }
                } catch (e: Exception) {
                    break
                }
            }
        }, "CastRelay").start()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        executor.shutdownNow()
        allowedHosts.clear()
    }

    /** Path and query (relative to this server) that relays [url]. */
    fun wrap(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        allowedHosts.add(host)
        return "/$secret/hls?u=${URLEncoder.encode(url, "UTF-8")}"
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = 30000
                val input = it.getInputStream()
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Range:", true)) {
                        range = header.substringAfter(':').trim()
                    }
                }
                val output = BufferedOutputStream(it.getOutputStream())
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)
                val target = parts.getOrNull(1) ?: return
                when {
                    method == "OPTIONS" -> writeHead(output, 204, null, 0)
                    method != "GET" && method != "HEAD" -> writeHead(output, 405, null, 0)
                    target.substringBefore('?') != "/$secret/hls" -> writeHead(output, 404, null, 0)
                    else -> {
                        val upstream = target.substringAfter("?u=", "").substringBefore('&')
                            .let { url -> URLDecoder.decode(url, "UTF-8") }.toHttpUrlOrNull()
                        if (upstream == null || upstream.host !in allowedHosts) {
                            writeHead(output, 403, null, 0)
                        } else {
                            relay(output, upstream, range, method == "HEAD")
                        }
                    }
                }
                output.flush()
            } catch (e: Exception) {
            }
        }
    }

    private fun relay(output: OutputStream, upstream: HttpUrl, range: String?, headOnly: Boolean) {
        val request = Request.Builder().url(upstream).apply {
            range?.let { header("Range", it) }
        }.build()
        client.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type")
            val isPlaylist = upstream.encodedPath.endsWith(".m3u8") || contentType?.contains("mpegurl", true) == true
            if (isPlaylist && response.isSuccessful) {
                val body = rewritePlaylist(response.body.string(), upstream).toByteArray()
                writeHead(output, response.code, "application/vnd.apple.mpegurl", body.size.toLong())
                if (!headOnly) output.write(body)
            } else {
                val extra = listOfNotNull(
                    response.header("Content-Range")?.let { "Content-Range" to it },
                    response.header("Accept-Ranges")?.let { "Accept-Ranges" to it },
                )
                writeHead(output, response.code, contentType ?: "application/octet-stream", response.body.contentLength(), extra)
                if (!headOnly) response.body.byteStream().copyTo(output)
            }
        }
    }

    private fun rewritePlaylist(text: String, base: HttpUrl): String {
        val lines = text.lines()
        val variants = lines.filter { it.startsWith("#EXT-X-STREAM-INF") }
        // only filter when it leaves at least one playable H.264 video variant
        val filterVariants = variants.any { isPlayableVariant(it) }
        val result = StringBuilder()
        var skipUri = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    if (filterVariants && !isPlayableVariant(line)) {
                        skipUri = true
                    } else {
                        result.append(line).append('\n')
                    }
                }
                line.startsWith("#") -> {
                    result.append(uriAttribute.replace(line) { match ->
                        base.resolve(match.groupValues[1])?.let { "URI=\"${relayUrl(it)}\"" } ?: match.value
                    }).append('\n')
                }
                line.isBlank() -> result.append(line).append('\n')
                skipUri -> skipUri = false
                else -> result.append(base.resolve(line.trim())?.let { relayUrl(it) } ?: line).append('\n')
            }
        }
        return result.toString()
    }

    private fun isPlayableVariant(streamInf: String): Boolean {
        val codecs = codecsAttribute.find(streamInf)?.groupValues?.get(1) ?: return true
        return codecs.contains("avc1") && unsupportedCodecs.none { codecs.contains(it) }
    }

    private fun relayUrl(url: HttpUrl): String {
        val isPlaylist = url.encodedPath.endsWith(".m3u8")
        return if (!isPlaylist && directSegmentHost.matches(url.host)) {
            url.toString()
        } else {
            allowedHosts.add(url.host)
            "/$secret/hls?u=${URLEncoder.encode(url.toString(), "UTF-8")}"
        }
    }

    private fun writeHead(output: OutputStream, code: Int, contentType: String?, contentLength: Long, extra: List<Pair<String, String>> = emptyList()) {
        val head = StringBuilder("HTTP/1.1 $code ${reasonPhrase(code)}\r\n").apply {
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
            contentType?.let { append("Content-Type: $it\r\n") }
            if (contentLength >= 0) append("Content-Length: $contentLength\r\n")
            extra.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toString().toByteArray())
    }

    private fun reasonPhrase(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Status"
    }

    private fun readLine(input: InputStream): String? {
        val bytes = StringBuilder()
        while (bytes.length < 8192) {
            val b = input.read()
            if (b == -1) return if (bytes.isEmpty()) null else bytes.toString()
            if (b == '\n'.code) return bytes.toString().trimEnd('\r')
            bytes.append(b.toChar())
        }
        return null
    }
}

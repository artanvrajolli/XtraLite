package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.ProxyOptions
import android.util.Base64
import androidx.core.net.toUri
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.writeObject
import com.apollographql.apollo.api.parseResponse
import com.github.andreyasadchy.xtra.db.CustomProxiesDao
import com.github.andreyasadchy.xtra.db.PlaybackStatesDao
import com.github.andreyasadchy.xtra.db.StreamProxiesDao
import com.github.andreyasadchy.xtra.db.VideoPositionsDao
import com.github.andreyasadchy.xtra.db.VideoSwapDao
import com.github.andreyasadchy.xtra.graphql.StreamPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoPosition
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.gql.playlist.PlaybackAccessTokenResponse
import com.github.andreyasadchy.xtra.model.ui.CustomProxy
import com.github.andreyasadchy.xtra.model.ui.StreamProxy
import com.github.andreyasadchy.xtra.model.ui.VideoSwap
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.QuicOptions
import org.chromium.net.apihelpers.UploadDataProviders
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.uuid.Uuid

class PlayerRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
    private val customProxiesDao: CustomProxiesDao,
    private val streamProxiesDao: StreamProxiesDao,
    private val videoSwapDao: VideoSwapDao,
    private val videoPositions: VideoPositionsDao,
    private val playbackStatesDao: PlaybackStatesDao,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) {

    suspend fun loadStreamPlaylistUrl(context: Context, networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, platform: String?, playerType: String?, supportedCodecs: String?, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, proxyTimeout: Int?, enableIntegrity: Boolean): String = withContext(Dispatchers.IO) {
        val platform = platform?.takeIf { it.isNotBlank() } ?: "web"
        val playerType = playerType?.takeIf { it.isNotBlank() } ?: "site"
        val accessToken = loadStreamPlaybackAccessToken(context, networkLibrary, gqlHeaders, channelLogin, platform, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, proxyTimeout, enableIntegrity).let { token ->
            if (token.second?.contains("\"forbidden\":true") == true && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                loadStreamPlaybackAccessToken(context, networkLibrary, gqlHeaders.filterNot { it.key == C.HEADER_TOKEN }, channelLogin, platform, playerType, proxyPlaybackAccessToken, proxyHost, proxyPort, proxyUser, proxyPassword, proxyTimeout, enableIntegrity)
            } else token
        }
        val signature = accessToken.first
        val token = accessToken.second
        "https://usher.ttvnw.net/api/v2/channel/hls/${channelLogin}.m3u8".toUri().buildUpon().apply {
            appendQueryParameter("allow_source", "true")
            appendQueryParameter("allow_audio_only", "true")
            appendQueryParameter("fast_bread", "true") // low latency
            appendQueryParameter("include_unavailable", "true")
            appendQueryParameter("p", Random.nextInt(9999999).toString())
            appendQueryParameter("platform", platform)
            signature?.let { appendQueryParameter("sig", it) }
            if (!supportedCodecs.isNullOrBlank()) {
                appendQueryParameter("supported_codecs", supportedCodecs)
            }
            token?.let { appendQueryParameter("token", it) }
        }.build().toString()
    }

    private suspend fun loadStreamPlaybackAccessToken(context: Context, networkLibrary: String?, gqlHeaders: Map<String, String>, channelLogin: String, platform: String, playerType: String, proxyPlaybackAccessToken: Boolean, proxyHost: String?, proxyPort: Int?, proxyUser: String?, proxyPassword: String?, proxyTimeout: Int?, enableIntegrity: Boolean): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val accessTokenHeaders = if (enableIntegrity) {
            gqlHeaders
        } else {
            gqlHeaders.toMutableMap().apply {
                put("X-Device-Id", Uuid.random().toHexString())
            }
        }
        val url = "https://gql.twitch.tv/gql"
        val proxyRequestHeaders = accessTokenHeaders.filterKeys { it == C.HEADER_CLIENT_ID || it == "X-Device-Id" }
        try {
            val response = if (proxyPlaybackAccessToken && !proxyHost.isNullOrBlank() && proxyPort != null) {
                val body = graphQLRepository.getPlaybackAccessTokenRequestBody(channelLogin, "", platform, playerType)
                when {
                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                            listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                        } else emptyList()
                        val builder = HttpEngine.Builder(context)
                        val httpEngine = try {
                            builder.setProxyOptions(ProxyOptions.fromProxyList(
                                listOf(
                                    android.net.http.Proxy.createHttpProxy(
                                        android.net.http.Proxy.SCHEME_HTTP,
                                        proxyHost,
                                        proxyPort,
                                        cronetExecutor.value,
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
                        if (httpEngine != null) {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.HttpEngineTimeout(proxyTimeout?.toLong() ?: 3000)
                                val request = httpEngine.newUrlRequestBuilder(
                                    url,
                                    cronetExecutor.value,
                                    NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                ).apply {
                                    proxyRequestHeaders.forEach { addHeader(it.key, it.value) }
                                    addHeader("Content-Type", "application/json")
                                    setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                                }.build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            json.decodeFromString<PlaybackAccessTokenResponse>(response.body.decodeToString())
                        } else {
                            okHttpClient.value.newBuilder().apply {
                                val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                    proxyAuthenticator { _, response ->
                                        response.request.newBuilder().header(
                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                        ).build()
                                    }
                                }
                            }.build().newCall(Request.Builder().apply {
                                url(url)
                                proxyRequestHeaders.forEach {
                                    addHeader(it.key, it.value)
                                }
                                header("Content-Type", "application/json")
                                post(body.toRequestBody())
                            }.build()).executeAsync().use { response ->
                                json.decodeFromString<PlaybackAccessTokenResponse>(response.body.string())
                            }
                        }
                    }
                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                        val cronetEngine = if (CronetProvider.getAllProviders(context).any { it.isEnabled }) {
                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                            } else emptyList()
                            val builder = CronetEngine.Builder(context).apply {
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
                                            cronetExecutor.value,
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
                        if (cronetEngine != null) {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.CronetTimeout(proxyTimeout?.toLong() ?: 3000)
                                val request = cronetEngine.newUrlRequestBuilder(
                                    url,
                                    NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                    cronetExecutor.value
                                ).apply {
                                    proxyRequestHeaders.forEach { addHeader(it.key, it.value) }
                                    addHeader("Content-Type", "application/json")
                                    setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                                }.build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            json.decodeFromString<PlaybackAccessTokenResponse>(response.body.decodeToString())
                        } else {
                            okHttpClient.value.newBuilder().apply {
                                val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                    proxyAuthenticator { _, response ->
                                        response.request.newBuilder().header(
                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                        ).build()
                                    }
                                }
                            }.build().newCall(Request.Builder().apply {
                                url(url)
                                proxyRequestHeaders.forEach {
                                    addHeader(it.key, it.value)
                                }
                                header("Content-Type", "application/json")
                                post(body.toRequestBody())
                            }.build()).executeAsync().use { response ->
                                json.decodeFromString<PlaybackAccessTokenResponse>(response.body.string())
                            }
                        }
                    }
                    else -> {
                        okHttpClient.value.newBuilder().apply {
                            val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                            connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header(
                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                    ).build()
                                }
                            }
                        }.build().newCall(Request.Builder().apply {
                            url(url)
                            proxyRequestHeaders.forEach {
                                addHeader(it.key, it.value)
                            }
                            header("Content-Type", "application/json")
                            post(body.toRequestBody())
                        }.build()).executeAsync().use { response ->
                            json.decodeFromString<PlaybackAccessTokenResponse>(response.body.string())
                        }
                    }
                }
            } else {
                graphQLRepository.loadPlaybackAccessToken(
                    networkLibrary = networkLibrary,
                    headers = accessTokenHeaders,
                    login = channelLogin,
                    platform = platform,
                    playerType = playerType,
                )
            }
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            response.data!!.streamPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        } catch (e: Exception) {
            if (e.message == C.FAILED_INTEGRITY_CHECK) throw e
            val response = if (proxyPlaybackAccessToken && !proxyHost.isNullOrBlank() && proxyPort != null) {
                val query = StreamPlaybackAccessTokenQuery(channelLogin, platform, playerType)
                val body = buildJsonString {
                    query.apply {
                        writeObject {
                            name("variables")
                            writeObject {
                                serializeVariables(this, CustomScalarAdapters.Empty, false)
                            }
                            name("query")
                            value(document().replaceFirst(name(), "null"))
                        }
                    }
                }
                when {
                    networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                        val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                            listOf(android.util.Pair("Proxy-Authorization", Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)))
                        } else emptyList()
                        val builder = HttpEngine.Builder(context)
                        val httpEngine = try {
                            builder.setProxyOptions(ProxyOptions.fromProxyList(
                                listOf(
                                    android.net.http.Proxy.createHttpProxy(
                                        android.net.http.Proxy.SCHEME_HTTP,
                                        proxyHost,
                                        proxyPort,
                                        cronetExecutor.value,
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
                        if (httpEngine != null) {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.HttpEngineTimeout(proxyTimeout?.toLong() ?: 3000)
                                val request = httpEngine.newUrlRequestBuilder(
                                    url,
                                    cronetExecutor.value,
                                    NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                                ).apply {
                                    proxyRequestHeaders.forEach { addHeader(it.key, it.value) }
                                    addHeader("Content-Type", "application/json")
                                    setUploadDataProvider(NetworkUtils.ByteArrayUploadProvider(body.toByteArray()), cronetExecutor.value)
                                }.build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            response.body.inputStream().source().buffer().jsonReader().use {
                                query.parseResponse(it)
                            }
                        } else {
                            okHttpClient.value.newBuilder().apply {
                                val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                    proxyAuthenticator { _, response ->
                                        response.request.newBuilder().header(
                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                        ).build()
                                    }
                                }
                            }.build().newCall(Request.Builder().apply {
                                url(url)
                                proxyRequestHeaders.forEach {
                                    addHeader(it.key, it.value)
                                }
                                header("Content-Type", "application/json")
                                post(body.toRequestBody())
                            }.build()).executeAsync().use { response ->
                                response.body.byteStream().source().buffer().jsonReader().use {
                                    query.parseResponse(it)
                                }
                            }
                        }
                    }
                    networkLibrary == C.CRONET && cronetEngine.value != null -> {
                        val cronetEngine = if (CronetProvider.getAllProviders(context).any { it.isEnabled }) {
                            val proxyHeaders = if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                mapOf("Proxy-Authorization" to Base64.encodeToString("$proxyUser:$proxyPassword".toByteArray(), Base64.NO_WRAP)).entries.toList()
                            } else emptyList()
                            val builder = CronetEngine.Builder(context).apply {
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
                                            cronetExecutor.value,
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
                        if (cronetEngine != null) {
                            val response = suspendCancellableCoroutine { continuation ->
                                val timeout = NetworkUtils.CronetTimeout(proxyTimeout?.toLong() ?: 3000)
                                val request = cronetEngine.newUrlRequestBuilder(
                                    url,
                                    NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                                    cronetExecutor.value
                                ).apply {
                                    proxyRequestHeaders.forEach { addHeader(it.key, it.value) }
                                    addHeader("Content-Type", "application/json")
                                    setUploadDataProvider(UploadDataProviders.create(body.toByteArray()), cronetExecutor.value)
                                }.build()
                                timeout.start(request, continuation)
                                request.start()
                                continuation.invokeOnCancellation {
                                    request.cancel()
                                    timeout.stop()
                                }
                            }
                            response.body.inputStream().source().buffer().jsonReader().use {
                                query.parseResponse(it)
                            }
                        } else {
                            okHttpClient.value.newBuilder().apply {
                                val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                                connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                                proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                                if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                    proxyAuthenticator { _, response ->
                                        response.request.newBuilder().header(
                                            "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                        ).build()
                                    }
                                }
                            }.build().newCall(Request.Builder().apply {
                                url(url)
                                proxyRequestHeaders.forEach {
                                    addHeader(it.key, it.value)
                                }
                                header("Content-Type", "application/json")
                                post(body.toRequestBody())
                            }.build()).executeAsync().use { response ->
                                response.body.byteStream().source().buffer().jsonReader().use {
                                    query.parseResponse(it)
                                }
                            }
                        }
                    }
                    else -> {
                        okHttpClient.value.newBuilder().apply {
                            val proxyTimeout = proxyTimeout?.toLong() ?: 3000
                            connectTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            writeTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            readTimeout(proxyTimeout, TimeUnit.MILLISECONDS)
                            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
                            if (!proxyUser.isNullOrBlank() && !proxyPassword.isNullOrBlank()) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder().header(
                                        "Proxy-Authorization", Credentials.basic(proxyUser, proxyPassword)
                                    ).build()
                                }
                            }
                        }.build().newCall(Request.Builder().apply {
                            url(url)
                            proxyRequestHeaders.forEach {
                                addHeader(it.key, it.value)
                            }
                            header("Content-Type", "application/json")
                            post(body.toRequestBody())
                        }.build()).executeAsync().use { response ->
                            response.body.byteStream().source().buffer().jsonReader().use {
                                query.parseResponse(it)
                            }
                        }
                    }
                }
            } else {
                graphQLRepository.loadQueryStreamPlaybackAccessToken(
                    networkLibrary = networkLibrary,
                    headers = accessTokenHeaders,
                    login = channelLogin,
                    platform = platform,
                    playerType = playerType,
                )
            }
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            response.data!!.streamPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        }
    }

    suspend fun loadVideoPlaylistUrl(networkLibrary: String?, gqlHeaders: Map<String, String>, videoId: String?, supportedCodecs: String?, enableIntegrity: Boolean): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val accessTokenHeaders = if (enableIntegrity) {
            gqlHeaders
        } else {
            gqlHeaders.toMutableMap().apply {
                put("X-Device-Id", Uuid.random().toHexString())
            }
        }
        val accessToken = try {
            val response = graphQLRepository.loadPlaybackAccessToken(
                networkLibrary = networkLibrary,
                headers = accessTokenHeaders,
                vodId = videoId,
                platform = "web",
                playerType = "site",
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            response.data!!.videoPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        } catch (e: Exception) {
            if (e.message == C.FAILED_INTEGRITY_CHECK) throw e
            val response = graphQLRepository.loadQueryVideoPlaybackAccessToken(
                networkLibrary = networkLibrary,
                headers = accessTokenHeaders,
                videoId = videoId!!,
                platform = "web",
                playerType = "site",
            )
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            response.data!!.videoPlaybackAccessToken!!.let {
                it.signature to it.value
            }
        }
        val signature = accessToken.first
        val token = accessToken.second
        val backupQualities = mutableListOf<String>()
        token?.let { value ->
            val json = try {
                JSONObject(value)
            } catch (e: JSONException) {
                null
            }
            val array = json?.optJSONObject("chansub")?.optJSONArray("restricted_bitrates")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val quality = array.optString(i)
                    if (!quality.isNullOrBlank()) {
                        backupQualities.add(quality)
                    }
                }
            }
        }
        val url = "https://usher.ttvnw.net/vod/v2/${videoId}.m3u8".toUri().buildUpon().apply {
            appendQueryParameter("allow_source", "true")
            appendQueryParameter("allow_audio_only", "true")
            appendQueryParameter("include_unavailable", "true")
            appendQueryParameter("p", Random.nextInt(9999999).toString())
            appendQueryParameter("platform", "web")
            signature?.let { appendQueryParameter("sig", it) }
            if (!supportedCodecs.isNullOrBlank()) {
                appendQueryParameter("supported_codecs", supportedCodecs)
            }
            token?.let { appendQueryParameter("token", it) }
        }.build().toString()
        url to backupQualities
    }

    suspend fun loadClipQualities(networkLibrary: String?, gqlHeaders: Map<String, String>, clipId: String?, supportedCodecs: String?, enableIntegrity: Boolean): List<VideoQuality>? = withContext(Dispatchers.IO) {
        try {
            val supportedCodecs = mutableListOf<String>().apply {
                if (!supportedCodecs.isNullOrBlank()) {
                    val list = supportedCodecs.split(',')
                    if (list.find { it.equals("h264", true) } != null) {
                        add("AVC")
                    }
                    if (list.find { it.equals("h265", true) } != null) {
                        add("HEVC")
                    }
                    if (list.find { it.equals("av1", true) } != null) {
                        add("AV1")
                    }
                }
            }
            val response = graphQLRepository.loadClipUrls(networkLibrary, gqlHeaders, clipId, supportedCodecs)
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            val accessToken = response.data?.clip?.playbackAccessToken
            response.data!!.clip.assets.let { assets ->
                (assets.maxByOrNull { it.aspectRatio ?: 0f } ?: assets.firstOrNull())?.videoQualities?.mapIndexedNotNull { index, quality ->
                    if (quality.sourceURL.isNotBlank()) {
                        val name = if (!quality.quality.isNullOrBlank()) {
                            val frameRate = quality.frameRate?.roundToInt() ?: ""
                            "${quality.quality}p${frameRate}"
                        } else {
                            index.toString()
                        }
                        val url = quality.sourceURL.toUri().buildUpon().apply {
                            appendQueryParameter("sig", accessToken?.signature)
                            appendQueryParameter("token", accessToken?.value)
                        }.build().toString()
                        VideoQuality(name, quality.quality?.toIntOrNull(), quality.frameRate, quality.bitrate, quality.codecs, url)
                    } else null
                }
            }
        } catch (e: Exception) {
            if (e.message == C.FAILED_INTEGRITY_CHECK) throw e
            val response = graphQLRepository.loadQueryClipUrls(networkLibrary, gqlHeaders, clipId!!)
            if (enableIntegrity) {
                response.errors?.find { it.message == C.FAILED_INTEGRITY_CHECK }?.let { throw Exception(it.message) }
            }
            val accessToken = response.data?.clip?.playbackAccessToken
            response.data?.clip?.assets?.let { assets ->
                (assets.maxByOrNull { it?.aspectRatio ?: 0.0 } ?: assets.firstOrNull())?.videoQualities?.mapIndexedNotNull { index, quality ->
                    if (!quality?.sourceURL.isNullOrBlank()) {
                        val name = if (!quality.quality.isNullOrBlank()) {
                            val frameRate = quality.frameRate?.roundToInt() ?: ""
                            "${quality.quality}p${frameRate}"
                        } else {
                            index.toString()
                        }
                        val url = quality.sourceURL.toUri().buildUpon().apply {
                            appendQueryParameter("sig", accessToken?.signature)
                            appendQueryParameter("token", accessToken?.value)
                        }.build().toString()
                        VideoQuality(name, quality.quality?.toIntOrNull(), quality.frameRate?.toFloat(), quality.bitrate, quality.codecs, url)
                    } else null
                }
            }
        }
    }

    suspend fun getCustomProxies() = withContext(Dispatchers.IO) {
        customProxiesDao.getAll()
    }

    suspend fun saveCustomProxies(items: List<CustomProxy>) = withContext(Dispatchers.IO) {
        customProxiesDao.insertList(items)
    }

    suspend fun updateCustomProxies(items: List<CustomProxy>) = withContext(Dispatchers.IO) {
        customProxiesDao.updateList(items)
    }

    suspend fun saveCustomProxy(item: CustomProxy): Long = withContext(Dispatchers.IO) {
        customProxiesDao.insert(item)
    }

    suspend fun deleteCustomProxy(item: CustomProxy) = withContext(Dispatchers.IO) {
        customProxiesDao.delete(item)
    }

    suspend fun updateCustomProxy(item: CustomProxy) = withContext(Dispatchers.IO) {
        customProxiesDao.update(item)
    }

    suspend fun getStreamProxies() = withContext(Dispatchers.IO) {
        streamProxiesDao.getAll()
    }

    suspend fun saveStreamProxies(items: List<StreamProxy>) = withContext(Dispatchers.IO) {
        streamProxiesDao.insertList(items)
    }

    suspend fun updateStreamProxies(items: List<StreamProxy>) = withContext(Dispatchers.IO) {
        streamProxiesDao.updateList(items)
    }

    suspend fun saveStreamProxy(item: StreamProxy): Long = withContext(Dispatchers.IO) {
        streamProxiesDao.insert(item)
    }

    suspend fun deleteStreamProxy(item: StreamProxy) = withContext(Dispatchers.IO) {
        streamProxiesDao.delete(item)
    }

    suspend fun updateStreamProxy(item: StreamProxy) = withContext(Dispatchers.IO) {
        streamProxiesDao.update(item)
    }

    suspend fun getVideoSwapItems() = withContext(Dispatchers.IO) {
        videoSwapDao.getAll()
    }

    suspend fun saveVideoSwapItems(items: List<VideoSwap>) = withContext(Dispatchers.IO) {
        videoSwapDao.insertList(items)
    }

    suspend fun updateVideoSwapItems(items: List<VideoSwap>) = withContext(Dispatchers.IO) {
        videoSwapDao.updateList(items)
    }

    suspend fun saveVideoSwap(item: VideoSwap): Long = withContext(Dispatchers.IO) {
        videoSwapDao.insert(item)
    }

    suspend fun deleteVideoSwap(item: VideoSwap) = withContext(Dispatchers.IO) {
        videoSwapDao.delete(item)
    }

    suspend fun updateVideoSwap(item: VideoSwap) = withContext(Dispatchers.IO) {
        videoSwapDao.update(item)
    }

    fun loadVideoPositions() = videoPositions.getAll()

    suspend fun getVideoPosition(id: Long) = withContext(Dispatchers.IO) {
        videoPositions.getById(id)
    }

    suspend fun saveVideoPosition(position: VideoPosition) = withContext(Dispatchers.IO) {
        videoPositions.insert(position)
    }

    suspend fun deleteVideoPositions() = withContext(Dispatchers.IO) {
        videoPositions.deleteAll()
    }

    suspend fun getPlaybackStates() = withContext(Dispatchers.IO) {
        playbackStatesDao.getAll()
    }

    suspend fun savePlaybackStates(items: List<PlaybackState>) = withContext(Dispatchers.IO) {
        playbackStatesDao.replaceItems(items)
    }

    suspend fun deletePlaybackStates() = withContext(Dispatchers.IO) {
        playbackStatesDao.deleteAll()
    }
}
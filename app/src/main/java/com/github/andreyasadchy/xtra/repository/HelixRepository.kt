package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import androidx.core.net.toUri
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelSearchResponse
import com.github.andreyasadchy.xtra.model.helix.clip.ClipsResponse
import com.github.andreyasadchy.xtra.model.helix.follow.FollowsResponse
import com.github.andreyasadchy.xtra.model.helix.game.GamesResponse
import com.github.andreyasadchy.xtra.model.helix.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.helix.user.UsersResponse
import com.github.andreyasadchy.xtra.model.helix.video.VideosResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.chromium.net.CronetEngine
import java.util.concurrent.ExecutorService

class HelixRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    suspend fun getGames(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, names: List<String>? = null): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            names?.forEach { appendQueryParameter("name", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getTopGames(networkLibrary: String?, headers: Map<String, String>, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/games/top".toUri().buildUpon().apply {
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getStreams(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null, gameId: String? = null, languages: List<String>? = null, limit: Int? = null, offset: String? = null): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("user_id", it) }
            logins?.forEach { appendQueryParameter("user_login", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            languages?.forEach { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<StreamsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getFollowedStreams(networkLibrary: String?, headers: Map<String, String>, userId: String?, limit: Int?, offset: String?): StreamsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/streams/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<StreamsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<StreamsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getClips(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, channelId: String? = null, gameId: String? = null, startedAt: String? = null, endedAt: String? = null, limit: Int? = null, offset: String? = null): ClipsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/clips".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            channelId?.let { appendQueryParameter("broadcaster_id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            startedAt?.let { appendQueryParameter("started_at", it) }
            endedAt?.let { appendQueryParameter("ended_at", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ClipsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ClipsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<ClipsResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getVideos(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, gameId: String? = null, channelId: String? = null, period: String? = null, broadcastType: String? = null, sort: String? = null, language: String? = null, limit: Int? = null, offset: String? = null): VideosResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/videos".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            gameId?.let { appendQueryParameter("game_id", it) }
            channelId?.let { appendQueryParameter("user_id", it) }
            period?.let { appendQueryParameter("period", it) }
            broadcastType?.let { appendQueryParameter("type", it) }
            sort?.let { appendQueryParameter("sort", it) }
            language?.let { appendQueryParameter("language", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<VideosResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<VideosResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<VideosResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUsers(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): UsersResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/users".toUri().buildUpon().apply {
            ids?.forEach { appendQueryParameter("id", it) }
            logins?.forEach { appendQueryParameter("login", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UsersResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<UsersResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<UsersResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchGames(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?): GamesResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/categories".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<GamesResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<GamesResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getSearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?, offset: String?, live: Boolean? = null): ChannelSearchResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/search/channels".toUri().buildUpon().apply {
            query?.let { appendQueryParameter("query", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
            live?.let { appendQueryParameter("live_only", it.toString()) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChannelSearchResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<ChannelSearchResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<ChannelSearchResponse>(response.body.string())
                }
            }
        }
    }

    suspend fun getUserFollows(networkLibrary: String?, headers: Map<String, String>, userId: String?, targetId: String? = null, limit: Int? = null, offset: String? = null): FollowsResponse = withContext(Dispatchers.IO) {
        val url = "https://api.twitch.tv/helix/channels/followed".toUri().buildUpon().apply {
            userId?.let { appendQueryParameter("user_id", it) }
            targetId?.let { appendQueryParameter("broadcaster_id", it) }
            limit?.let { appendQueryParameter("first", it.toString()) }
            offset?.let { appendQueryParameter("after", it) }
        }.build().toString()
        when {
            networkLibrary == C.HTTP_ENGINE && httpEngine.value != null -> @SuppressLint("NewApi") {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            networkLibrary == C.CRONET && cronetEngine.value != null -> {
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.CronetTimeout()
                    val request = cronetEngine.value!!.newUrlRequestBuilder(
                        url,
                        NetworkUtils.ByteArrayCronetCallback(continuation, timeout),
                        cronetExecutor.value
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
                    }.build()
                    timeout.start(request, continuation)
                    request.start()
                    continuation.invokeOnCancellation {
                        request.cancel()
                        timeout.stop()
                    }
                }
                json.decodeFromString<FollowsResponse>(response.body.decodeToString())
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                }.build()).executeAsync().use { response ->
                    json.decodeFromString<FollowsResponse>(response.body.string())
                }
            }
        }
    }

}
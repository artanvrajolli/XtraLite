package com.github.andreyasadchy.xtra.repository

import android.annotation.SuppressLint
import android.net.http.HttpEngine
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.json.writeObject
import com.apollographql.apollo.api.parseResponse
import com.github.andreyasadchy.xtra.graphql.ClipQuery
import com.github.andreyasadchy.xtra.graphql.ClipUrlsQuery
import com.github.andreyasadchy.xtra.graphql.GameBoxArtQuery
import com.github.andreyasadchy.xtra.graphql.GameClipsQuery
import com.github.andreyasadchy.xtra.graphql.GameQuery
import com.github.andreyasadchy.xtra.graphql.GameStreamsQuery
import com.github.andreyasadchy.xtra.graphql.GameVideosQuery
import com.github.andreyasadchy.xtra.graphql.SearchChannelsQuery
import com.github.andreyasadchy.xtra.graphql.SearchFreeformTagsQuery
import com.github.andreyasadchy.xtra.graphql.SearchGameTagsQuery
import com.github.andreyasadchy.xtra.graphql.SearchGamesQuery
import com.github.andreyasadchy.xtra.graphql.SearchStreamsQuery
import com.github.andreyasadchy.xtra.graphql.SearchVideosQuery
import com.github.andreyasadchy.xtra.graphql.SelfFollowingGameQuery
import com.github.andreyasadchy.xtra.graphql.SelfFollowingUserQuery
import com.github.andreyasadchy.xtra.graphql.StreamPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.graphql.TagQuery
import com.github.andreyasadchy.xtra.graphql.TeamLiveMembersQuery
import com.github.andreyasadchy.xtra.graphql.TeamMembersQuery
import com.github.andreyasadchy.xtra.graphql.TeamQuery
import com.github.andreyasadchy.xtra.graphql.TopGamesQuery
import com.github.andreyasadchy.xtra.graphql.TopStreamsQuery
import com.github.andreyasadchy.xtra.graphql.UserAboutQuery
import com.github.andreyasadchy.xtra.graphql.UserChannelPageQuery
import com.github.andreyasadchy.xtra.graphql.UserChattersQuery
import com.github.andreyasadchy.xtra.graphql.UserClipsQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedGamesQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedStreamsQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedUsersQuery
import com.github.andreyasadchy.xtra.graphql.UserFollowedVideosQuery
import com.github.andreyasadchy.xtra.graphql.UserQuery
import com.github.andreyasadchy.xtra.graphql.UserResultIDQuery
import com.github.andreyasadchy.xtra.graphql.UserResultLoginQuery
import com.github.andreyasadchy.xtra.graphql.UserVideosQuery
import com.github.andreyasadchy.xtra.graphql.UsersLastBroadcastQuery
import com.github.andreyasadchy.xtra.graphql.UsersStreamQuery
import com.github.andreyasadchy.xtra.graphql.UsersTypeQuery
import com.github.andreyasadchy.xtra.graphql.VideoMomentsQuery
import com.github.andreyasadchy.xtra.graphql.VideoPlaybackAccessTokenQuery
import com.github.andreyasadchy.xtra.graphql.VideoQuery
import com.github.andreyasadchy.xtra.graphql.type.BroadcastType
import com.github.andreyasadchy.xtra.graphql.type.ClipsPeriod
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.gql.ErrorResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelClipsResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelSuggestionsResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelVideosResponse
import com.github.andreyasadchy.xtra.model.gql.channel.ChannelViewerListResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipDataResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipUrlsResponse
import com.github.andreyasadchy.xtra.model.gql.clip.ClipVideoResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedChannelsResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedGamesResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedStreamsResponse
import com.github.andreyasadchy.xtra.model.gql.followed.FollowedVideosResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameClipsResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameStreamsResponse
import com.github.andreyasadchy.xtra.model.gql.game.GameVideosResponse
import com.github.andreyasadchy.xtra.model.gql.game.GamesResponse
import com.github.andreyasadchy.xtra.model.gql.playlist.PlaybackAccessTokenResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchChannelsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchGameTagsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchGamesResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchStreamTagsResponse
import com.github.andreyasadchy.xtra.model.gql.search.SearchVideosResponse
import com.github.andreyasadchy.xtra.model.gql.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.gql.stream.ViewerCountResponse
import com.github.andreyasadchy.xtra.model.gql.tag.TagResponse
import com.github.andreyasadchy.xtra.model.gql.video.VideoGamesResponse
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.NetworkUtils
import com.github.andreyasadchy.xtra.util.NetworkUtils.executeAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source
import org.chromium.net.CronetEngine
import org.chromium.net.apihelpers.UploadDataProviders
import java.util.concurrent.ExecutorService
import kotlin.uuid.Uuid

class GraphQLRepository(
    private val httpEngine: Lazy<HttpEngine?>,
    private val cronetEngine: Lazy<CronetEngine?>,
    private val cronetExecutor: Lazy<ExecutorService>,
    private val okHttpClient: Lazy<OkHttpClient>,
    private val json: Json,
) {

    private suspend fun <T: Query.Data> sendQuery(networkLibrary: String?, headers: Map<String, String>, query: Query<T>): ApolloResponse<T> = withContext(Dispatchers.IO) {
        val url = "https://gql.twitch.tv/gql"
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
                val response = suspendCancellableCoroutine { continuation ->
                    val timeout = NetworkUtils.HttpEngineTimeout()
                    val request = httpEngine.value!!.newUrlRequestBuilder(
                        url,
                        cronetExecutor.value,
                        NetworkUtils.ByteArrayUrlCallback(continuation, timeout)
                    ).apply {
                        headers.forEach { addHeader(it.key, it.value) }
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
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.body.byteStream().source().buffer().jsonReader().use {
                        query.parseResponse(it)
                    }
                }
            }
        }
    }

    private suspend fun sendPersistedQuery(networkLibrary: String?, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val url = "https://gql.twitch.tv/gql"
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
                response.body.decodeToString()
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
                response.body.decodeToString()
            }
            else -> {
                okHttpClient.value.newCall(Request.Builder().apply {
                    url(url)
                    headers(headers.toHeaders())
                    header("Content-Type", "application/json")
                    post(body.toRequestBody())
                }.build()).executeAsync().use { response ->
                    response.body.string()
                }
            }
        }
    }

    suspend fun loadQueryClip(networkLibrary: String?, headers: Map<String, String>, slug: String): ApolloResponse<ClipQuery.Data> = withContext(Dispatchers.IO) {
        val query = ClipQuery(slug)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryClipUrls(networkLibrary: String?, headers: Map<String, String>, slug: String): ApolloResponse<ClipUrlsQuery.Data> = withContext(Dispatchers.IO) {
        val query = ClipUrlsQuery(slug)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGame(networkLibrary: String?, headers: Map<String, String>, id: String? = null, slug: String? = null, name: String? = null): ApolloResponse<GameQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameBoxArt(networkLibrary: String?, headers: Map<String, String>, id: String? = null, name: String? = null): ApolloResponse<GameBoxArtQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameBoxArtQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameClips(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, languages: List<Language>?, period: ClipsPeriod?, first: Int?, after: String?): ApolloResponse<GameClipsQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameClipsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            languages = Optional.Present(languages),
            period = Optional.Present(period),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameStreams(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, sort: StreamSort?, tags: List<String>?, languages: List<Language>?, first: Int?, after: String?): ApolloResponse<GameStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameStreamsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            sort = Optional.Present(sort),
            tags = Optional.Present(tags),
            languages = Optional.Present(languages),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryGameVideos(networkLibrary: String?, headers: Map<String, String>, id: String?, slug: String?, name: String?, languages: List<String>?, sort: VideoSort?, type: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<GameVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = GameVideosQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
            languages = Optional.Present(languages),
            sort = Optional.Present(sort),
            type = Optional.Present(type),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchChannelsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchChannelsQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchFreeformTags(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?): ApolloResponse<SearchFreeformTagsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchFreeformTagsQuery(
            query = query,
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchGames(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchGamesQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchGameTags(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?): ApolloResponse<SearchGameTagsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchGameTagsQuery(
            query = query,
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchStreams(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchStreamsQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQuerySearchVideos(networkLibrary: String?, headers: Map<String, String>, query: String, first: Int?, after: String?): ApolloResponse<SearchVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = SearchVideosQuery(
            query = query,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryFollowingGame(networkLibrary: String?, headers: Map<String, String>, id: String? = null, slug: String? = null, name: String? = null): ApolloResponse<SelfFollowingGameQuery.Data> = withContext(Dispatchers.IO) {
        val query = SelfFollowingGameQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            slug = if (!slug.isNullOrBlank()) Optional.Present(slug) else Optional.Absent,
            name = if (!name.isNullOrBlank()) Optional.Present(name) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryFollowingUser(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?): ApolloResponse<SelfFollowingUserQuery.Data> = withContext(Dispatchers.IO) {
        val query = SelfFollowingUserQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryStreamPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, login: String, platform: String, playerType: String): ApolloResponse<StreamPlaybackAccessTokenQuery.Data> = withContext(Dispatchers.IO) {
        val query = StreamPlaybackAccessTokenQuery(login, platform, playerType)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTag(networkLibrary: String?, headers: Map<String, String>, id: String): ApolloResponse<TagQuery.Data> = withContext(Dispatchers.IO) {
        val query = TagQuery(
            id = id
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeam(networkLibrary: String?, headers: Map<String, String>, name: String): ApolloResponse<TeamQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamQuery(
            name = name,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeamLiveMembers(networkLibrary: String?, headers: Map<String, String>, name: String, first: Int?, after: String?): ApolloResponse<TeamLiveMembersQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamLiveMembersQuery(
            name = name,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTeamMembers(networkLibrary: String?, headers: Map<String, String>, name: String, first: Int?, after: String?): ApolloResponse<TeamMembersQuery.Data> = withContext(Dispatchers.IO) {
        val query = TeamMembersQuery(
            name = name,
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTopGames(networkLibrary: String?, headers: Map<String, String>, tags: List<String>?, first: Int?, after: String?): ApolloResponse<TopGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = TopGamesQuery(
            tags = Optional.Present(tags),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryTopStreams(networkLibrary: String?, headers: Map<String, String>, sort: StreamSort?, tags: List<String>?, languages: List<Language>?, first: Int?, after: String?): ApolloResponse<TopStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = TopStreamsQuery(
            sort = Optional.Present(sort),
            tags = Optional.Present(tags),
            languages = Optional.Present(languages),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUser(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserAbout(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?): ApolloResponse<UserAboutQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserAboutQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserChannelPage(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserChannelPageQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserChannelPageQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserChatters(networkLibrary: String?, headers: Map<String, String>, id: String? = null, login: String? = null): ApolloResponse<UserChattersQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserChattersQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserClips(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?, period: ClipsPeriod?, first: Int?, after: String?): ApolloResponse<UserClipsQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserClipsQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            period = Optional.Present(period),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedGames(networkLibrary: String?, headers: Map<String, String>, first: Int?): ApolloResponse<UserFollowedGamesQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedGamesQuery(
            first = Optional.Present(first),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedStreams(networkLibrary: String?, headers: Map<String, String>, first: Int?, after: String?): ApolloResponse<UserFollowedStreamsQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedStreamsQuery(
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedUsers(networkLibrary: String?, headers: Map<String, String>, first: Int?, after: String?): ApolloResponse<UserFollowedUsersQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedUsersQuery(
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserFollowedVideos(networkLibrary: String?, headers: Map<String, String>, sort: VideoSort?, type: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<UserFollowedVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserFollowedVideosQuery(
            sort = Optional.Present(sort),
            type = Optional.Present(type),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserResultID(networkLibrary: String?, headers: Map<String, String>, id: String): ApolloResponse<UserResultIDQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserResultIDQuery(id)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserResultLogin(networkLibrary: String?, headers: Map<String, String>, login: String): ApolloResponse<UserResultLoginQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserResultLoginQuery(login)
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUserVideos(networkLibrary: String?, headers: Map<String, String>, id: String?, login: String?, sort: VideoSort?, types: List<BroadcastType>?, first: Int?, after: String?): ApolloResponse<UserVideosQuery.Data> = withContext(Dispatchers.IO) {
        val query = UserVideosQuery(
            id = if (!id.isNullOrBlank()) Optional.Present(id) else Optional.Absent,
            login = if (!login.isNullOrBlank()) Optional.Present(login) else Optional.Absent,
            sort = Optional.Present(sort),
            types = Optional.Present(types),
            first = Optional.Present(first),
            after = Optional.Present(after),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersLastBroadcast(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersLastBroadcastQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersLastBroadcastQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (ids.isNullOrEmpty() && !logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersStream(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersStreamQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersStreamQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (!logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryUsersType(networkLibrary: String?, headers: Map<String, String>, ids: List<String>? = null, logins: List<String>? = null): ApolloResponse<UsersTypeQuery.Data> = withContext(Dispatchers.IO) {
        val query = UsersTypeQuery(
            ids = if (!ids.isNullOrEmpty()) Optional.Present(ids) else Optional.Absent,
            logins = if (!logins.isNullOrEmpty()) Optional.Present(logins) else Optional.Absent,
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideo(networkLibrary: String?, headers: Map<String, String>, id: String?): ApolloResponse<VideoQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoQuery(Optional.Present(id))
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideoMoments(networkLibrary: String?, headers: Map<String, String>, videoId: String?): ApolloResponse<VideoMomentsQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoMomentsQuery(
            id = Optional.Present(videoId),
            first = Optional.Present(100),
            after = Optional.Present(null),
        )
        sendQuery(networkLibrary, headers, query)
    }

    suspend fun loadQueryVideoPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, videoId: String, platform: String, playerType: String): ApolloResponse<VideoPlaybackAccessTokenQuery.Data> = withContext(Dispatchers.IO) {
        val query = VideoPlaybackAccessTokenQuery(videoId, platform, playerType)
        sendQuery(networkLibrary, headers, query)
    }

    fun getPlaybackAccessTokenRequestBody(login: String?, vodId: String?, platform: String?, playerType: String?): String {
        return buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9")
                    put("version", 1)
                }
            }
            put("operationName", "PlaybackAccessToken")
            putJsonObject("variables") {
                put("isLive", !login.isNullOrBlank())
                put("login", login ?: "")
                put("isVod", !vodId.isNullOrBlank())
                put("vodID", vodId ?: "")
                put("playerType", playerType)
                put("platform", platform)
            }
        }.toString()
    }

    suspend fun loadPlaybackAccessToken(networkLibrary: String?, headers: Map<String, String>, login: String? = null, vodId: String? = null, platform: String?, playerType: String?): PlaybackAccessTokenResponse = withContext(Dispatchers.IO) {
        val body = getPlaybackAccessTokenRequestBody(login, vodId, platform, playerType)
        json.decodeFromString<PlaybackAccessTokenResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipUrls(networkLibrary: String?, headers: Map<String, String>, slug: String?, supportedCodecs: List<String>): ClipUrlsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "c5ca7c4143e42f257b91b97b37fbb19206460f42f564a02095bb504fd0a31af8")
                    put("version", 1)
                }
            }
            put("operationName", "VideoAccessToken_Clip")
            putJsonObject("variables") {
                put("slug", slug)
                put("platform", "web")
                putJsonArray("supportedCodecs") {
                    supportedCodecs.forEach {
                        add(it)
                    }
                }
            }
        }.toString()
        json.decodeFromString<ClipUrlsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipData(networkLibrary: String?, headers: Map<String, String>, slug: String?): ClipDataResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "a33067cdf92191dccfb53aa86f878a2c271e6a3587a6731dc8275e5751dd133f")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelClipCore")
            putJsonObject("variables") {
                put("clipSlug", slug)
            }
        }.toString()
        json.decodeFromString<ClipDataResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadClipVideo(networkLibrary: String?, headers: Map<String, String>, slug: String?): ClipVideoResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "9aa558e066a22227c5ef2c0a8fded3aaa57d35181ad15f63df25bff516253a90")
                    put("version", 1)
                }
            }
            put("operationName", "ChatClip")
            putJsonObject("variables") {
                put("clipSlug", slug)
            }
        }.toString()
        json.decodeFromString<ClipVideoResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTag(networkLibrary: String?, headers: Map<String, String>, id: String): TagResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "bb28b8b7b08b55ce39d25ba8bfb0aa6c9fad53b8a89f8b4377f59db405c3fb26")
                    put("version", 1)
                }
            }
            put("operationName", "TagHandlerTag")
            putJsonObject("variables") {
                put("id", id)
            }
        }.toString()
        json.decodeFromString<TagResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTopGames(networkLibrary: String?, headers: Map<String, String>, tags: List<String>?, limit: Int?, cursor: String?): GamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2f67f71ba89f3c0ed26a141ec00da1defecb2303595f5cda4298169549783d9e")
                    put("version", 1)
                }
            }
            put("operationName", "BrowsePage_AllDirectories")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("limit", limit)
                putJsonObject("options") {
                    put("sort", "VIEWER_COUNT")
                    putJsonArray("tags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                }
            }
        }.toString()
        json.decodeFromString<GamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadTopStreams(networkLibrary: String?, headers: Map<String, String>, sort: String?, tags: List<String>?, languages: List<String>?, limit: Int?, cursor: String?): StreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "fb60a7f9b2fe8f9c9a080f41585bd4564bea9d3030f4d7cb8ab7f9e99b1cee67")
                    put("version", 1)
                }
            }
            put("operationName", "BrowsePage_Popular")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
                putJsonObject("options") {
                    putJsonArray("broadcasterLanguages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    putJsonArray("freeformTags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                    put("sort", sort)
                }
                put("platformType", "all")
                put("sortTypeIsRecency", sort == "RECENT")
            }
        }.toString()
        json.decodeFromString<StreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameStreams(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, sort: String?, tags: List<String>?, languages: List<String>?, limit: Int?, cursor: String?): GameStreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "76cb069d835b8a02914c08dc42c421d0dafda8af5b113a3f19141824b901402f")
                    put("version", 1)
                }
            }
            put("operationName", "DirectoryPage_Game")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
                putJsonObject("options") {
                    putJsonArray("broadcasterLanguages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    putJsonArray("freeformTags") {
                        tags?.forEach {
                            add(it)
                        }
                    }
                    put("sort", sort)
                }
                put("slug", gameSlug)
                put("sortTypeIsRecency", sort == "RECENT")
            }
        }.toString()
        json.decodeFromString<GameStreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameVideos(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, type: String?, sort: String?, languages: List<String>?, limit: Int?, cursor: String?): GameVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "d3f2564d36b095cb5bbe78a66b7f9d0618934fa3f49f5b9321d29c41cd0d2d0c")
                    put("version", 1)
                }
            }
            put("operationName", "DirectoryVideos_Game")
            putJsonObject("variables") {
                if (type != null) {
                    putJsonArray("broadcastTypes") {
                        add(type)
                    }
                }
                put("followedCursor", cursor)
                put("includePreviewBlur", false)
                putJsonArray("languages") {
                    languages?.forEach {
                        add(it)
                    }
                }
                put("slug", gameSlug)
                put("videoLimit", limit)
                put("videoSort", sort)
            }
        }.toString()
        json.decodeFromString<GameVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameClips(networkLibrary: String?, headers: Map<String, String>, gameSlug: String?, period: String?, languages: List<String>?, limit: Int?, cursor: String?): GameClipsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2211af9c7b963fa324a7ca1b2e2b5ad823c1cee5a73adcd8220b6eba10776e20")
                    put("version", 1)
                }
            }
            put("operationName", "ClipsCards__Game")
            putJsonObject("variables") {
                put("categorySlug", gameSlug)
                putJsonObject("criteria") {
                    put("filter", period)
                    putJsonArray("languages") {
                        languages?.forEach {
                            add(it)
                        }
                    }
                    put("shouldFilterByDiscoverySetting", false)
                }
                put("cursor", cursor)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<GameClipsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelSuggestions(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelSuggestionsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b9660765905e84e7b6a1ed18937b49ef0569e9b2a1c8f7a40a1bf289fbe2ced6")
                    put("version", 1)
                }
            }
            put("operationName", "SideNav")
            putJsonObject("variables") {
                put("creatorAnniversariesFeature", false)
                putJsonObject("input") {
                    put("contextChannelName", channelLogin)
                    putJsonObject("recommendationContext") {
                        put("channelName", channelLogin)
                        put("clientApp", "twilight")
                        put("location", "channel")
                        put("platform", "web")
                    }
                }
                put("isLoggedIn", headers[C.HEADER_TOKEN] != null)
                put("withFreeformTags", true)
            }
        }.toString()
        val headers = if (headers["X-Device-Id"] == null) {
            headers.toMutableMap().apply {
                put("X-Device-Id", Uuid.random().toHexString())
            }
        } else headers
        json.decodeFromString<ChannelSuggestionsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelVideos(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?, type: String?, sort: String?, limit: Int?, cursor: String?): ChannelVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "53dc989e1d3cc360f7e4868c88eaaf37e06495b1d58c91c435e9e83643bb8c5c")
                    put("version", 1)
                }
            }
            put("operationName", "FilterableVideoTower_Videos")
            putJsonObject("variables") {
                put("broadcastType", type)
                put("channelOwnerLogin", channelLogin)
                put("cursor", cursor)
                put("includePreviewBlur", false)
                put("limit", limit)
                put("videoSort", sort)
            }
        }.toString()
        json.decodeFromString<ChannelVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelClips(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?, period: String?, limit: Int?, cursor: String?): ChannelClipsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b06901ff6be502ab854ac2ceb8462a6b3bee2d76c750c20cbecb4907273bd2aa")
                    put("version", 1)
                }
            }
            put("operationName", "ClipsCards__User")
            putJsonObject("variables") {
                putJsonObject("criteria") {
                    put("filter", period)
                    put("shouldFilterByDiscoverySetting", false)
                }
                put("cursor", cursor)
                put("limit", limit)
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ChannelClipsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchChannels(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchChannelsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "CHANNEL")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchChannelsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchGames(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "GAME")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadSearchVideos(networkLibrary: String?, headers: Map<String, String>, query: String?, cursor: String?): SearchVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "7f3580f6ac6cd8aa1424cff7c974a07143827d6fa36bba1b54318fe7f0b68dc5")
                    put("version", 1)
                }
            }
            put("operationName", "SearchResultsPage_SearchResults")
            putJsonObject("variables") {
                put("includeIsDJ", true)
                putJsonObject("options") {
                    put("shouldSkipDiscoveryControl", true)
                    putJsonArray("targets") {
                        add(buildJsonObject {
                            if (cursor != null) {
                                put("cursor", cursor)
                            }
                            put("index", "VOD")
                        })
                    }
                }
                put("query", query)
            }
        }.toString()
        json.decodeFromString<SearchVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFreeformTags(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?): SearchStreamTagsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "8bc91a618bb5f0c5f9bc19195028c9f4a6a1b8651cf5bd8e4f2408124cdf465a")
                    put("version", 1)
                }
            }
            put("operationName", "SearchFreeformTags")
            putJsonObject("variables") {
                put("first", limit)
                put("userQuery", query ?: "")
            }
        }.toString()
        json.decodeFromString<SearchStreamTagsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadGameTags(networkLibrary: String?, headers: Map<String, String>, query: String?, limit: Int?): SearchGameTagsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b4cb189d8d17aadf29c61e9d7c7e7dcfc932e93b77b3209af5661bffb484195f")
                    put("version", 1)
                }
            }
            put("operationName", "SearchCategoryTags")
            putJsonObject("variables") {
                put("limit", limit)
                put("userQuery", query ?: "")
            }
        }.toString()
        json.decodeFromString<SearchGameTagsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    private fun getVideoMessagesRequestBody(videoId: String?, offset: Int?, cursor: String?): String {
        return buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a")
                    put("version", 1)
                }
            }
            put("operationName", "VideoCommentsByOffsetOrCursor")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("contentOffsetSeconds", offset)
                put("videoID", videoId)
            }
        }.toString()
    }

    suspend fun loadVideoGames(networkLibrary: String?, headers: Map<String, String>, videoId: String?): VideoGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "71835d5ef425e154bf282453a926d99b328cdc5e32f36d3a209d0f4778b41203")
                    put("version", 1)
                }
            }
            put("operationName", "VideoPlayer_ChapterSelectButtonVideo")
            putJsonObject("variables") {
                put("videoID", videoId)
            }
        }.toString()
        json.decodeFromString<VideoGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadChannelViewerList(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ChannelViewerListResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2e71a3399875770c1e5d81a9774d9803129c44cf8f6bad64973aa0d239a88caf")
                    put("version", 1)
                }
            }
            put("operationName", "CommunityTab")
            putJsonObject("variables") {
                put("login", channelLogin)
            }
        }.toString()
        json.decodeFromString<ChannelViewerListResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadViewerCount(networkLibrary: String?, headers: Map<String, String>, channelLogin: String?): ViewerCountResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "00b11c9c428f79ae228f30080a06ffd8226a1f068d6f52fbc057cbde66e994c2")
                    put("version", 1)
                }
            }
            put("operationName", "UseViewCount")
            putJsonObject("variables") {
                put("channelLogin", channelLogin)
            }
        }.toString()
        json.decodeFromString<ViewerCountResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedStreams(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedStreamsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "de3e2d0230fdb0a1da1e91e535ad839ddedaecc2c9c462c1d35d97e7b0b425ae")
                    put("version", 1)
                }
            }
            put("operationName", "FollowingLive_CurrentUser")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("imageWidth", 50)
                put("includeCostreaming", true)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<FollowedStreamsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedVideos(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedVideosResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f2c1304cb0c2c46436ca48778c5b17d486750d7beeabe3bb9719fc47d765f2c8")
                    put("version", 1)
                }
            }
            put("operationName", "FollowedVideos_CurrentUser")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("includePreviewBlur", false)
                put("limit", limit)
            }
        }.toString()
        json.decodeFromString<FollowedVideosResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedChannels(networkLibrary: String?, headers: Map<String, String>, limit: Int?, cursor: String?): FollowedChannelsResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "eecf815273d3d949e5cf0085cc5084cd8a1b5b7b6f7990cf43cb0beadf546907")
                    put("version", 1)
                }
            }
            put("operationName", "ChannelFollows")
            putJsonObject("variables") {
                put("cursor", cursor)
                put("limit", limit)
                put("order", "DESC")
            }
        }.toString()
        json.decodeFromString<FollowedChannelsResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowedGames(networkLibrary: String?, headers: Map<String, String>, limit: Int?): FollowedGamesResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f3c5d45175d623ed3d5ff4ca4c7de379ea6a1a4852236087dc1b81b7dbfd3114")
                    put("version", 1)
                }
            }
            put("operationName", "FollowingGames_CurrentUser")
            putJsonObject("variables") {
                put("limit", limit)
                put("type", "ALL")
            }
        }.toString()
        json.decodeFromString<FollowedGamesResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowUser(networkLibrary: String?, headers: Map<String, String>, userId: String?, disableNotifications: Boolean): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "800e7346bdf7e5278a3c1d3f21b2b56e2639928f86815677a7126b093b2fdd08")
                    put("version", 1)
                }
            }
            put("operationName", "FollowButton_FollowUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("disableNotifications", disableNotifications)
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadUnfollowUser(networkLibrary: String?, headers: Map<String, String>, userId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "f7dae976ebf41c755ae2d758546bfd176b4eeb856656098bb40e0a672ca0d880")
                    put("version", 1)
                }
            }
            put("operationName", "FollowButton_UnfollowUser")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadToggleNotificationsUser(networkLibrary: String?, headers: Map<String, String>, userId: String?, disableNotifications: Boolean): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "2319a2486246f63b13ffc0d1c317c89df177150185352791a81eb7bced0128a1")
                    put("version", 1)
                }
            }
            put("operationName", "LiveNotificationsToggle_ToggleNotifications")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("disableNotifications", disableNotifications)
                    put("targetID", userId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadFollowGame(networkLibrary: String?, headers: Map<String, String>, gameId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "b846b65ba4bc9a3561dbe2d069d95deed9b9e031bcfda2482d1bedd84a1c2eb3")
                    put("version", 1)
                }
            }
            put("operationName", "FollowGameButton_FollowGame")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("gameID", gameId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

    suspend fun loadUnfollowGame(networkLibrary: String?, headers: Map<String, String>, gameId: String?): ErrorResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("sha256Hash", "811e02e396ebba0664f21ff002f2eff3c6f57e8af9aedb4f4dfa77cefd0db43d")
                    put("version", 1)
                }
            }
            put("operationName", "FollowGameButton_UnfollowGame")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("gameID", gameId)
                }
            }
        }.toString()
        json.decodeFromString<ErrorResponse>(sendPersistedQuery(networkLibrary, headers, body))
    }

}
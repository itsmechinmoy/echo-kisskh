package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.providers.SettingsProvider
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingMultipleChoice
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class KissKHExtension :
    MusicExtension,
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    ShareClient,
    SettingsProvider {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var settings: Settings? = null

    private val baseUrl: String
        get() = settings?.getString(PREF_DOMAIN_KEY) ?: PREF_DOMAIN_DEFAULT

    private val subDecryptor: SubDecryptor
        get() = SubDecryptor(client, baseUrl)

    private val titleUriRegex by lazy { Regex("[^a-zA-Z0-9]") }

    override suspend fun onExtensionSelected() {}

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    // --- Network Helpers ---

    private fun getHeaders(): Headers {
        return Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$baseUrl/")
            .add("Origin", baseUrl)
            .build()
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .headers(getHeaders())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw Exception("Empty body for $url")
        }
    }

    private fun requestVideoKey(id: String): String {
        val url = "$KISSKH_API$id&version=2.8.10"
        val body = httpGet(url)
        return json.decodeFromString<KeyResponseDto>(body).key ?: throw Exception("Failed to get video key")
    }

    private fun requestSubKey(id: String): String {
        val url = "$KISSKH_SUB_API$id&version=2.8.10"
        val body = httpGet(url)
        return json.decodeFromString<KeyResponseDto>(body).key ?: throw Exception("Failed to get sub key")
    }

    // --- Home Feed ---

    override suspend fun getTabs(): List<Tab> {
        return listOf(
            Tab("popular", "Popular"),
            Tab("latest", "Latest Updates"),
        )
    }

    override suspend fun loadFeed(tab: Tab): Feed<Shelf> {
        val order = if (tab.id == "latest") "2" else "1"
        val pagedData = PagedData.Continuous<EchoMediaItem> { continuation ->
            withContext(Dispatchers.IO) {
                val page = continuation?.toIntOrNull() ?: 1
                val url = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=$order&pageSize=40"
                val body = httpGet(url)
                val response = json.decodeFromString<DramaListResponseDto>(body)
                val items = response.data?.mapNotNull { item ->
                    val id = item.id?.toString() ?: return@mapNotNull null
                    val title = item.title ?: return@mapNotNull null
                    val cover = item.thumbnail?.toImageHolder()
                    EchoMediaItem.Lists.AlbumItem(
                        Album(
                            id = id,
                            title = title,
                            cover = cover,
                            artists = emptyList()
                        )
                    )
                } ?: emptyList()

                val totalCount = response.totalCount ?: 0
                val totalPages = (totalCount + 39) / 40
                val nextPage = if (page < totalPages) (page + 1).toString() else null

                Page(items, nextPage)
            }
        }
        val shelf = Shelf.Media(title = tab.title, data = pagedData)
        return Feed(listOf(shelf))
    }

    // --- Search Feed ---

    override suspend fun searchFeed(query: String): Feed<Shelf> {
        val pagedData = PagedData.Single<EchoMediaItem> {
            withContext(Dispatchers.IO) {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$baseUrl/api/DramaList/Search?q=$encoded&type=0"
                val body = httpGet(url)
                val items = json.decodeFromString<List<DramaItemDto>>(body).mapNotNull { item ->
                    val id = item.id?.toString() ?: return@mapNotNull null
                    val title = item.title ?: return@mapNotNull null
                    val cover = item.thumbnail?.toImageHolder()
                    EchoMediaItem.Lists.AlbumItem(
                        Album(
                            id = id,
                            title = title,
                            cover = cover,
                            artists = emptyList()
                        )
                    )
                }
                items
            }
        }
        return Feed(listOf(Shelf.Media(title = "Search Results", data = pagedData)))
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/DramaList/Search?q=$encoded&type=0"
            val body = httpGet(url)
            json.decodeFromString<List<DramaItemDto>>(body).take(10).mapNotNull { item ->
                val id = item.id?.toString() ?: return@mapNotNull null
                val title = item.title ?: return@mapNotNull null
                val cover = item.thumbnail?.toImageHolder()
                QuickSearchItem.SearchItem(
                    title = title,
                    item = EchoMediaItem.Lists.AlbumItem(
                        Album(
                            id = id,
                            title = title,
                            cover = cover,
                            artists = emptyList()
                        )
                    )
                )
            }
        }
    }

    // --- Album Client (Drama Details & Episodes) ---

    override suspend fun loadAlbum(album: Album): Album {
        return withContext(Dispatchers.IO) {
            val dramaId = album.id.substringAfter("id=").substringBefore("&")
            val url = "$baseUrl/api/DramaList/Drama/$dramaId?isq=false"
            val body = httpGet(url)
            val drama = json.decodeFromString<DramaDetailDto>(body)

            val desc = buildString {
                drama.status?.let { append("Status: $it\n") }
                drama.country?.let { append("Country: $it\n") }
                drama.type?.let { append("Type: $it\n") }
                drama.episodesCount?.let { append("Episodes: $it\n\n") }
                drama.description?.let { append(it) }
            }

            album.copy(
                title = drama.title ?: album.title,
                description = desc,
                cover = drama.thumbnail?.toImageHolder() ?: album.cover,
                tracks = loadTracks(album)
            )
        }
    }

    override suspend fun loadTracks(album: Album): List<Track> {
        return withContext(Dispatchers.IO) {
            val dramaId = album.id.substringAfter("id=").substringBefore("&")
            val url = "$baseUrl/api/DramaList/Drama/$dramaId?isq=false"
            val body = httpGet(url)
            val drama = json.decodeFromString<DramaDetailDto>(body)
            val type = drama.type
            val episodesCount = drama.episodesCount ?: 1

            drama.episodes?.mapNotNull { ep ->
                val episodeId = ep.id?.toString() ?: return@mapNotNull null
                val number = ep.number?.toString()?.replace(".0", "") ?: "1"
                val name = when {
                    type.isNullOrBlank() -> "Video $number"
                    (type.contains("Hollywood", ignoreCase = true) && episodesCount == 1) || type.contains("Movie", ignoreCase = true) -> "Movie"
                    else -> "Episode $number"
                }

                Track(
                    id = "${dramaId}_$episodeId",
                    title = name,
                    type = Track.Type.Video,
                    album = album,
                    cover = drama.thumbnail?.toImageHolder() ?: album.cover,
                    artists = emptyList(),
                    extras = mapOf(
                        "dramaId" to dramaId,
                        "episodeId" to episodeId,
                    )
                )
            } ?: emptyList()
        }
    }

    // --- Track Client (Stream & Subtitle Extraction) ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return withContext(Dispatchers.IO) {
            val episodeId = track.extras["episodeId"]
                ?: track.id.substringAfter("_")

            val streamables = mutableListOf<Streamable>()

            // Default Video Server
            streamables.add(
                Streamable.server(
                    id = "server_$episodeId",
                    quality = 1080,
                    title = "KissKH Server",
                    extras = mapOf("episodeId" to episodeId)
                )
            )

            // Subtitles
            try {
                val kkey = requestSubKey(episodeId)
                val subData = httpGet("$baseUrl/api/Sub/$episodeId?kkey=$kkey")
                val subList = json.decodeFromString<List<SubtitleItemDto>>(subData)
                subList.forEachIndexed { idx, sub ->
                    val src = sub.src ?: return@forEachIndexed
                    val label = sub.label ?: "Subtitle $idx"
                    streamables.add(
                        Streamable.subtitle(
                            id = "sub_${episodeId}_$idx",
                            title = label,
                            extras = mapOf(
                                "subUrl" to src,
                                "label" to label
                            )
                        )
                    )
                }
            } catch (_: Exception) {
            }

            track.copy(
                streamables = streamables
            )
        }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        return withContext(Dispatchers.IO) {
            when (streamable.type) {
                Streamable.MediaType.Server -> {
                    val episodeId = streamable.extras["episodeId"]
                        ?: streamable.id.removePrefix("server_")

                    val kkey = requestVideoKey(episodeId)
                    val url = "$baseUrl/api/DramaList/Episode/$episodeId.png?err=false&ts=&time=&kkey=$kkey"
                    val body = httpGet(url)
                    val videoDto = json.decodeFromString<VideoResponseDto>(body)
                    val videoUrl = videoDto.Video
                        ?: throw Exception("No stream available for episode $episodeId")

                    val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
                    val source = Streamable.Source.Http(
                        request = videoUrl.toGetRequest(
                            mapOf(
                                "Referer" to "$baseUrl/",
                                "Origin" to baseUrl,
                                "User-Agent" to USER_AGENT
                            )
                        ),
                        type = if (isHls) Streamable.SourceType.HLS else Streamable.SourceType.Progressive,
                        isVideo = true,
                        quality = 1080,
                        title = "KissKH"
                    )

                    Streamable.Media.Server(listOf(source), merged = false)
                }

                Streamable.MediaType.Subtitle -> {
                    val subUrl = streamable.extras["subUrl"]
                        ?: throw Exception("Subtitle URL not found")

                    if (subUrl.contains(".txt", ignoreCase = true)) {
                        val decryptedUri = subDecryptor.getSubtitles(subUrl)
                        Streamable.Media.Subtitle(
                            url = decryptedUri,
                            type = Streamable.SubtitleType.SRT
                        )
                    } else {
                        val type = if (subUrl.contains(".vtt", ignoreCase = true)) {
                            Streamable.SubtitleType.VTT
                        } else {
                            Streamable.SubtitleType.SRT
                        }
                        Streamable.Media.Subtitle(
                            url = subUrl,
                            type = type
                        )
                    }
                }

                else -> throw Exception("Unsupported streamable type: ${streamable.type}")
            }
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    // --- Share Client ---

    override suspend fun share(item: EchoMediaItem): String? {
        return when (item) {
            is EchoMediaItem.Lists.AlbumItem -> {
                val album = item.album
                val titleUri = album.title.replace(titleUriRegex, "-")
                "$baseUrl/Drama/$titleUri?id=${album.id}"
            }
            is EchoMediaItem.TrackItem -> {
                val track = item.track
                val album = track.album
                val titleUri = (album?.title ?: track.title).replace(titleUriRegex, "-")
                val dramaId = track.extras["dramaId"] ?: album?.id ?: ""
                val epId = track.extras["episodeId"] ?: ""
                "$baseUrl/Drama/$titleUri?id=$dramaId&ep=$epId"
            }
            else -> null
        }
    }

    // --- Settings Provider ---

    override fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                key = PREF_DOMAIN_KEY,
                title = "Preferred Domain",
                description = "Choose the domain to access KissKH",
                items = listOf(
                    SettingMultipleChoice(
                        key = "pref_domain_choice",
                        title = "Domain",
                        entries = DOMAIN_ENTRIES,
                        entryValues = DOMAIN_VALUES,
                        defaultValue = PREF_DOMAIN_DEFAULT
                    )
                )
            )
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val KISSKH_API =
            "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="

        private const val KISSKH_SUB_API =
            "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "kisskh.ovh",
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}

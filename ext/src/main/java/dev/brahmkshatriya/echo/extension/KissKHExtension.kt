package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingList
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
    ExtensionClient,
    HomeFeedClient,
    SearchFeedClient,
    QuickSearchClient,
    AlbumClient,
    TrackClient,
    LyricsClient,
    ShareClient {

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

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = mutableListOf(
            Tab("popular", "Popular", false),
            Tab("latest", "Latest Updates", false),
        )
        return Feed(tabs) { tab ->
            val pagedData = PagedData.Continuous<Shelf> { continuation ->
                val page = continuation?.toIntOrNull() ?: 1
                val order = if (tab?.id == "latest") "2" else "1"
                val url = "$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=$order&pageSize=40"
                val body = httpGet(url)
                val response = json.decodeFromString<DramaListResponseDto>(body)
                val items = response.data?.mapNotNull { item ->
                    val id = item.id?.toString() ?: return@mapNotNull null
                    val title = item.title ?: return@mapNotNull null
                    val cover = item.thumbnail?.toImageHolder()
                    Album(
                        id = id,
                        title = title,
                        cover = cover,
                        artists = emptyList(),
                    ).toShelf()
                } ?: emptyList()

                val totalCount = response.totalCount ?: 0
                val totalPages = (totalCount + 39) / 40
                val nextPage = if (page < totalPages) (page + 1).toString() else null

                Page(items, nextPage)
            }
            pagedData.toFeedData()
        }
    }

    // --- Search Feed ---

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val pagedData = PagedData.Continuous<Shelf> { continuation ->
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/DramaList/Search?q=$encoded&type=0"
            val body = httpGet(url)
            val items = json.decodeFromString<List<DramaItemDto>>(body).mapNotNull { item ->
                val id = item.id?.toString() ?: return@mapNotNull null
                val title = item.title ?: return@mapNotNull null
                val cover = item.thumbnail?.toImageHolder()
                Album(
                    id = id,
                    title = title,
                    cover = cover,
                    artists = emptyList(),
                ).toShelf()
            }
            Page(items, null)
        }
        return pagedData.toFeed()
    }

    override suspend fun quickSearch(query: String): List<QuickSearchItem> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/DramaList/Search?q=$encoded&type=0"
            val body = httpGet(url)
            json.decodeFromString<List<DramaItemDto>>(body).take(8).mapNotNull { item ->
                val id = item.id?.toString() ?: return@mapNotNull null
                val title = item.title ?: return@mapNotNull null
                val cover = item.thumbnail?.toImageHolder()
                QuickSearchItem.Media(
                    Album(
                        id = id,
                        title = title,
                        cover = cover,
                        artists = emptyList(),
                    ),
                    false
                )
            }
        }
    }

    override suspend fun deleteQuickSearch(item: QuickSearchItem) {}

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
            )
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        return withContext(Dispatchers.IO) {
            val dramaId = album.id.substringAfter("id=").substringBefore("&")
            val url = "$baseUrl/api/DramaList/Drama/$dramaId?isq=false"
            val body = httpGet(url)
            val drama = json.decodeFromString<DramaDetailDto>(body)
            val type = drama.type
            val episodesCount = drama.episodesCount ?: 1

            val tracks = drama.episodes?.mapNotNull { ep ->
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

            tracks.toFeed()
        }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

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

            // Subtitles for Video Player
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

    // --- Lyrics Client (Softsubs in Lyrics Tab) ---

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        return withContext(Dispatchers.IO) {
            val episodeId = track.extras["episodeId"] ?: track.id.substringAfter("_")
            val list = try {
                val kkey = requestSubKey(episodeId)
                val subData = httpGet("$baseUrl/api/Sub/$episodeId?kkey=$kkey")
                val subList = json.decodeFromString<List<SubtitleItemDto>>(subData)
                subList.mapIndexedNotNull { idx, sub ->
                    val src = sub.src ?: return@mapIndexedNotNull null
                    val label = sub.label ?: "Subtitle $idx"
                    Lyrics(
                        id = "lyrics_${episodeId}_$idx",
                        title = label,
                        subtitle = sub.land,
                        lyrics = null,
                        extras = mapOf(
                            "subUrl" to src,
                            "label" to label,
                        )
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
            list.toFeed()
        }
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        return withContext(Dispatchers.IO) {
            val subUrl = lyrics.extras["subUrl"] ?: lyrics.id
            val content = if (subUrl.contains(".txt", ignoreCase = true)) {
                subDecryptor.decryptToString(subUrl)
            } else {
                val request = Request.Builder()
                    .url(subUrl)
                    .headers(getHeaders())
                    .get()
                    .build()
                client.newCall(request).execute().use { res ->
                    res.body?.string() ?: ""
                }
            }

            val items = parseSubtitleToTimedLyrics(content)
            lyrics.copy(lyrics = Lyrics.Lyric.Timed(items, fillTimeGaps = true))
        }
    }

    private fun parseSubtitleToTimedLyrics(content: String): List<Lyrics.Item> {
        val items = mutableListOf<Lyrics.Item>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = TIMESTAMP_REGEX.find(line)
            if (match != null) {
                val startHours = match.groupValues[1].toLongOrNull() ?: 0L
                val startMinutes = match.groupValues[2].toLongOrNull() ?: 0L
                val startSeconds = match.groupValues[3].toLongOrNull() ?: 0L
                val startMillis = match.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val startTime = ((startHours * 3600 + startMinutes * 60 + startSeconds) * 1000) + startMillis

                val endHours = match.groupValues[5].toLongOrNull() ?: 0L
                val endMinutes = match.groupValues[6].toLongOrNull() ?: 0L
                val endSeconds = match.groupValues[7].toLongOrNull() ?: 0L
                val endMillis = match.groupValues[8].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val endTime = ((endHours * 3600 + endMinutes * 60 + endSeconds) * 1000) + endMillis

                val textBuilder = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    val textLine = lines[i].trim()
                    if (!TIMESTAMP_REGEX.matches(textLine) && !textLine.matches(Regex("""^\d+$"""))) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(textLine.replace(Regex("<[^>]*>"), ""))
                    }
                    i++
                }

                val text = textBuilder.toString().trim()
                if (text.isNotBlank()) {
                    items.add(Lyrics.Item(text = text, startTime = startTime, endTime = endTime))
                }
            } else {
                i++
            }
        }
        return items
    }

    // --- Share Client ---

    override suspend fun onShare(item: EchoMediaItem): String {
        return when (item) {
            is Album -> {
                val titleUri = item.title.replace(titleUriRegex, "-")
                "$baseUrl/Drama/$titleUri?id=${item.id}"
            }
            is Track -> {
                val album = item.album
                val titleUri = (album?.title ?: item.title).replace(titleUriRegex, "-")
                val dramaId = item.extras["dramaId"] ?: album?.id ?: ""
                val epId = item.extras["episodeId"] ?: ""
                "$baseUrl/Drama/$titleUri?id=$dramaId&ep=$epId"
            }
            else -> baseUrl
        }
    }

    // --- Settings ---

    override suspend fun getSettingItems(): List<Setting> {
        return listOf(
            SettingList(
                key = PREF_DOMAIN_KEY,
                title = "Preferred Domain",
                summary = "Choose the domain to access KissKH",
                entryTitles = DOMAIN_ENTRIES.toMutableList(),
                entryValues = DOMAIN_VALUES.toMutableList(),
                defaultEntryIndex = 0
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
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
            "kisskh.ovh",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        private val TIMESTAMP_REGEX =
            Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{2,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{2,3})""")
    }
}

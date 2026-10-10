package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DramaPageDto(
    val page: Int? = null,
    val totalCount: Int? = null,
    val data: List<DramaItemDto>? = emptyList(),
)

@Serializable
data class DramaItemDto(
    val id: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
)

@Serializable
data class DramaDetailDto(
    val id: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val status: String? = null,
    val country: String? = null,
    val type: String? = null,
    val episodesCount: Int? = null,
    val trailer: String? = null,
    val episodes: List<EpisodeDto>? = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: Int? = null,
    val number: Double? = null,
    val sub: Int? = null,
)

@Serializable
data class EpisodeVideoDto(
    @SerialName("Video")
    val video: String? = null,
    @SerialName("Type")
    val type: Int? = null,
    @SerialName("ThirdParty")
    val thirdParty: String? = null,
)

@Serializable
data class SubtitleItemDto(
    val src: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
    val land: String? = null,
)

@Serializable
data class KeyResponseDto(
    val id: String? = null,
    val version: String? = null,
    val key: String? = null,
)

package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
data class DramaListResponseDto(
    val data: List<DramaItemDto>? = null,
    val page: Int? = null,
    val totalCount: Int? = null,
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
    val episodes: List<EpisodeDto>? = null,
)

@Serializable
data class EpisodeDto(
    val id: Int? = null,
    val number: Double? = null,
    val sub: Int? = null,
)

@Serializable
data class VideoResponseDto(
    val Video: String? = null,
    val ThirdParty: String? = null,
)

@Serializable
data class KeyResponseDto(
    val id: String? = null,
    val version: String? = null,
    val key: String? = null,
)

@Serializable
data class SubtitleItemDto(
    val src: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
    val land: String? = null,
)

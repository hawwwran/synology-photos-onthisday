package com.hawwwran.photosonthisday.api

import kotlinx.serialization.Serializable

/** The `data` of a `Browse.Item` `list` response. Only the fields the grid and the fetch need. */
@Serializable
data class ItemListData(val list: List<ItemDto> = emptyList())

@Serializable
data class ItemDto(
    val id: Int,
    val time: Long,
    val type: String,
    val additional: ItemAdditional? = null,
)

@Serializable
data class ItemAdditional(
    val thumbnail: ThumbnailDto? = null,
    val resolution: ResolutionDto? = null,
)

@Serializable
data class ThumbnailDto(val cache_key: String, val unit_id: Int)

@Serializable
data class ResolutionDto(val width: Int = 0, val height: Int = 0)

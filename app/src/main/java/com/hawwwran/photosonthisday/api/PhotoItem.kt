package com.hawwwran.photosonthisday.api

/**
 * One photo or video of one day, enough to place it in the grid and fetch its thumbnail.
 * [unitId] and [cacheKey] address the thumbnail; the two came out equal to `id` on every
 * sampled item, but the thumbnail endpoint keys on the unit, so it is what is kept.
 */
data class PhotoItem(
    val space: Space,
    val id: Int,
    val unitId: Int,
    val cacheKey: String,
    val takenTimeSeconds: Long,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
)

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
    /** For the info sheet only; empty/0 when the source did not carry it. Never logged. */
    val filename: String = "",
    val filesize: Long = 0,
    /** The Photos folder this item lives in, resolved to a path lazily for the info sheet. */
    val folderId: Int = 0,
)

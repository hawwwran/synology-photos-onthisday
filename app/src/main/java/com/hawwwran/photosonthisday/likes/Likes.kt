package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Space
import kotlinx.serialization.Serializable

/** Stable key for a liked item across the two namespaces: e.g. "PERSONAL:246724". */
fun likeKey(space: Space, unitId: Int): String = "${space.name}:$unitId"

/**
 * The inverse of [likeKey], or null for anything else. The likes file is user-editable and may be
 * written by another device or a later version, so a key is data to check, never to destructure:
 * `"abc"`, `"FOO:5"` and `":5"` are all null, and a null row is skipped rather than persisted.
 */
fun parseLikeKey(key: String): Pair<Space, Int>? {
    val colon = key.indexOf(':')
    if (colon <= 0) return null
    val space = spaceOrNull(key.substring(0, colon)) ?: return null
    val unitId = key.substring(colon + 1).toIntOrNull() ?: return null
    return space to unitId
}

/** `Space.valueOf` that answers null instead of throwing, for a namespace read back from storage. */
fun spaceOrNull(name: String): Space? = Space.entries.firstOrNull { it.name == name }

/** One item's like, with when it last changed, for last-writer-wins merge (decision 008). */
data class LikeState(val key: String, val liked: Boolean, val updatedAt: Long)

/** The on-disk shape of the NAS likes file. Records both likes and unlikes so an unlike propagates. */
@Serializable
data class LikesFile(val version: Int = 1, val likes: List<LikeRecord> = emptyList())

@Serializable
data class LikeRecord(val key: String, val liked: Boolean, val at: Long)

fun LikesFile.toStates(): List<LikeState> = likes.map { LikeState(it.key, it.liked, it.at) }

fun Collection<LikeState>.toFile(): LikesFile = LikesFile(likes = map { LikeRecord(it.key, it.liked, it.updatedAt) })

/**
 * Merge two sets of like states, newer `updatedAt` winning per key (decision 008). Idempotent and
 * order-independent, which is what makes two devices safe to reconcile.
 */
object LikesMerge {
    fun merge(a: List<LikeState>, b: List<LikeState>): Map<String, LikeState> {
        val out = HashMap<String, LikeState>()
        for (state in a) out[state.key] = state
        for (state in b) {
            val current = out[state.key]
            if (current == null || state.updatedAt > current.updatedAt) out[state.key] = state
        }
        return out
    }
}

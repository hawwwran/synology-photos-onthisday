package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Space
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LikesTest {

    @Test
    fun `like key distinguishes the namespaces`() {
        assertEquals("PERSONAL:5", likeKey(Space.PERSONAL, 5))
        assertEquals("SHARED:5", likeKey(Space.SHARED, 5))
    }

    @Test
    fun `likes file round-trips through json`() {
        val file = LikesFile(likes = listOf(LikeRecord("PERSONAL:1", true, 100), LikeRecord("SHARED:2", false, 200)))
        val json = Json.encodeToString(LikesFile.serializer(), file)
        assertEquals(file, Json.decodeFromString(LikesFile.serializer(), json))
    }

    @Test
    fun `merge keeps the newer state per key`() {
        val local = listOf(LikeState("a", liked = true, updatedAt = 10), LikeState("b", liked = true, updatedAt = 50))
        val remote = listOf(LikeState("a", liked = false, updatedAt = 20), LikeState("c", liked = true, updatedAt = 5))

        val merged = LikesMerge.merge(local, remote)

        assertFalse("a: remote unlike is newer", merged.getValue("a").liked)
        assertTrue("b: only local has it", merged.getValue("b").liked)
        assertTrue("c: only remote has it", merged.getValue("c").liked)
        assertEquals(setOf("a", "b", "c"), merged.keys)
    }

    @Test
    fun `merge is order independent`() {
        val x = listOf(LikeState("k", true, 1))
        val y = listOf(LikeState("k", false, 2))
        assertEquals(LikesMerge.merge(x, y)["k"], LikesMerge.merge(y, x)["k"])
    }
}

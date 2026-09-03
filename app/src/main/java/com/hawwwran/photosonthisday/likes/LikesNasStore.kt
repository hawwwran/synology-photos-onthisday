package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Allowlist
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.ApiLog
import com.hawwwran.photosonthisday.api.AppJson
import com.hawwwran.photosonthisday.api.MalformedDetail
import com.hawwwran.photosonthisday.api.SessionCredentials
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

/** The durable copy of the likes, wherever it lives. An interface so the repository is tested without a NAS. */
interface LikesRemote {
    /**
     * Every state in the file, or an empty list when the file does not exist yet. A file that exists
     * but cannot be read throws [ApiFailure.Malformed]: decision 008 forbids overwriting it.
     */
    suspend fun pull(baseUrl: HttpUrl, folder: String, credentials: SessionCredentials): List<LikeState>

    suspend fun push(baseUrl: HttpUrl, folder: String, states: Collection<LikeState>, credentials: SessionCredentials)
}

/**
 * The likes file on the NAS (decision 008): download it, parse it, write it back. The folder is a
 * setting so it can point at a share the account can write; the default is the account's home.
 */
class LikesNasStore(
    private val fileStation: FileStationClient,
    private val json: Json = AppJson,
) : LikesRemote {
    override suspend fun pull(baseUrl: HttpUrl, folder: String, credentials: SessionCredentials): List<LikeState> {
        val bytes = fileStation.download(baseUrl, "$folder/$FILE_NAME", credentials) ?: return emptyList()
        return try {
            json.decodeFromString(LikesFile.serializer(), bytes.decodeToString()).toStates()
        } catch (e: SerializationException) {
            throw unreadable()
        } catch (e: IllegalArgumentException) {
            throw unreadable()
        }
    }

    /** Present but not our shape: stop, so the sync never pushes over it. Logged as the call and a detail, no content. */
    private fun unreadable(): ApiFailure.Malformed =
        ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, MalformedDetail.UNREADABLE_LIKES_FILE).also(ApiLog::failure)

    override suspend fun push(baseUrl: HttpUrl, folder: String, states: Collection<LikeState>, credentials: SessionCredentials) {
        val bytes = json.encodeToString(LikesFile.serializer(), states.toFile()).encodeToByteArray()
        fileStation.upload(baseUrl, folder, FILE_NAME, bytes, credentials)
    }

    private companion object {
        const val FILE_NAME = "likes.json"
    }
}

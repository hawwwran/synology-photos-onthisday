package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.SessionCredentials
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

/**
 * The likes file on the NAS (decision 008): download it, parse it, write it back. The folder is a
 * setting so it can point at a share the account can write; the default is the account's home.
 */
class LikesNasStore(
    private val fileStation: FileStationClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun pull(baseUrl: HttpUrl, folder: String, credentials: SessionCredentials): List<LikeState> {
        val bytes = fileStation.download(baseUrl, "$folder/$FILE_NAME", credentials) ?: return emptyList()
        return try {
            json.decodeFromString(LikesFile.serializer(), bytes.decodeToString()).toStates()
        } catch (e: SerializationException) {
            emptyList() // a corrupt or foreign file is treated as no likes rather than a crash
        }
    }

    suspend fun push(baseUrl: HttpUrl, folder: String, states: Collection<LikeState>, credentials: SessionCredentials) {
        val bytes = json.encodeToString(LikesFile.serializer(), states.toFile()).encodeToByteArray()
        fileStation.upload(baseUrl, folder, FILE_NAME, bytes, credentials)
    }

    private companion object {
        const val FILE_NAME = "likes.json"
    }
}

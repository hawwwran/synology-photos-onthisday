package com.hawwwran.photosonthisday.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

/**
 * `Browse.Folder`, used only to turn an item's `folder_id` into its path for the info sheet. A
 * read on the allowlist. The response's `folder.name` is the folder's full path from the space
 * root (e.g. "/Mobil/2020"). The shape was confirmed live against Photos 1.9.1 rather than
 * assumed; see documents/research/photos-web-api.md.
 */
class FolderApi(private val client: SynologyClient) {

    /** The folder's path, or null if it cannot be resolved (never throws to the caller). */
    suspend fun path(baseUrl: HttpUrl, space: Space, folderId: Int, credentials: SessionCredentials): String? {
        if (folderId <= 0) return null
        val call = Allowlist.folderGet(space)
        return try {
            val data = client.callObject(baseUrl, call, mapOf("id" to folderId.toString()), credentials)
            // Safe casts, not `.jsonObject`: a surprising shape here must omit the path, not crash the sheet.
            ((data["folder"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
        } catch (e: ApiFailure) {
            null // the info sheet simply omits the path; the failure is already logged by call name and code
        }
    }
}

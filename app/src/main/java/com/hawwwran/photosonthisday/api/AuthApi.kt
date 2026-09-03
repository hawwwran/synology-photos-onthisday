package com.hawwwran.photosonthisday.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl

/** What a successful login yields. The password is not here and never was stored anywhere. */
data class LoginResult(
    val credentials: SessionCredentials,
    /** DSM's trusted-device id, `device_id` in the response. Absent or empty when none. */
    val deviceId: String?,
)

/** `SYNO.API.Auth` v7, the shape recorded under "Signing in" in the research file. */
class AuthApi(private val client: SynologyClient) {

    /**
     * One attempt. Never called in a loop: DSM auto-block bans the address after a few
     * failures, and a retry here would lock the household out of its own NAS.
     *
     * [password] is read once into the form body and dropped with it. [deviceId] is the trusted
     * device from a previous two-factor login, which lets DSM skip the code.
     */
    suspend fun login(
        baseUrl: HttpUrl,
        account: String,
        password: String,
        otpCode: String? = null,
        deviceId: String? = null,
    ): LoginResult {
        val params = buildMap {
            put("account", account)
            put("passwd", password)
            put("format", "sid")
            put("enable_syno_token", "yes")
            put("enable_device_token", "yes")
            put("device_name", DEVICE_NAME)
            if (!otpCode.isNullOrBlank()) put("otp_code", otpCode.trim())
            if (!deviceId.isNullOrBlank()) put("device_id", deviceId)
        }
        val data = client.callObject(baseUrl, Allowlist.LOGIN, params)
        val sid = data.string("sid") ?: throw ApiFailure.Malformed(Allowlist.LOGIN, "success without a sid")
        return LoginResult(
            credentials = SessionCredentials(sid = sid, synotoken = data.string("synotoken")),
            deviceId = data.string("device_id"),
        )
    }

    suspend fun logout(baseUrl: HttpUrl, credentials: SessionCredentials) {
        client.call(baseUrl, Allowlist.LOGOUT, credentials = credentials)
    }

    /** A non-empty string field, or null when absent, empty, or not a primitive. Never throws. */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    companion object {
        /** Shows up in DSM's connected-devices list, so it should say which app this is. */
        const val DEVICE_NAME = "On This Day"
    }
}

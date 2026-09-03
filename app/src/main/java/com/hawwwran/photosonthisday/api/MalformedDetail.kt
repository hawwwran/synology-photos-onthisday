package com.hawwwran.photosonthisday.api

/**
 * Everything [ApiFailure.Malformed.detail] may say. App-authored strings only: a status line the
 * app read off the response, or a shape the app could not parse. Never a byte of the body, so a
 * detail is safe to log and safe to show. Built here so the screen can recognise a status again
 * ([httpStatus]) instead of parsing prose.
 */
object MalformedDetail {
    const val UNREADABLE_LIKES_FILE = "likes file is not readable"
    const val ENVELOPE_INSTEAD_OF_FILE = "download answered an envelope, not a file"

    fun http(code: Int): String = "$HTTP_PREFIX$code"

    /** The status in [detail] when it came from [http], else null. */
    fun httpStatus(detail: String): Int? = detail.removePrefix(HTTP_PREFIX).takeIf { it != detail }?.toIntOrNull()

    private const val HTTP_PREFIX = "HTTP "
}

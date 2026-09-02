package com.hawwwran.photosonthisday.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The outcome of reading a NAS address the user typed. */
sealed interface BaseUrlResult {
    data class Ok(val url: HttpUrl) : BaseUrlResult
    data class Refused(val reason: String) : BaseUrlResult
}

/**
 * The one place a typed address becomes an [HttpUrl], and therefore the one place the
 * HTTPS rule of decision 004 is enforced: `http://` is refused with the reason, and a bare
 * host is read as `https://host`. Nothing downstream re-checks the scheme.
 */
fun parseBaseUrl(text: String): BaseUrlResult {
    val trimmed = text.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
        return BaseUrlResult.Refused("Enter the NAS address, for example https://nas.example.com")
    }
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val url = withScheme.toHttpUrlOrNull()
        ?: return BaseUrlResult.Refused("That is not a valid address")
    if (!url.isHttps) {
        return BaseUrlResult.Refused(
            "Only https:// is accepted. Over http:// the password would cross the network unprotected.",
        )
    }
    return BaseUrlResult.Ok(url)
}

package com.hawwwran.photosonthisday.api

/** One Synology entry point. The triple has to be on the [Allowlist] before a request exists. */
data class ApiCall(val api: String, val method: String, val version: Int) {
    /** What the log names. Never a parameter, never a body. */
    val name: String get() = "$api.$method v$version"
}

/** Photos keeps personal and shared photos in two API namespaces with identical shapes. */
enum class Space(val apiPrefix: String) {
    PERSONAL("SYNO.Foto"),
    SHARED("SYNO.FotoTeam"),
}

/**
 * Every `(api, method, version)` this app may call, from `documents/research/photos-web-api.md`,
 * observed on Photos 1.9.1. A triple absent here throws before any request is built (plan.md
 * §2). It is an allowlist, not a blocklist: some Photos read methods use POST, so the verb
 * cannot classify safety, and an unknown endpoint must be refused rather than attempted.
 */
object Allowlist {
    val API_INFO = ApiCall("SYNO.API.Info", "query", 1)
    val LOGIN = ApiCall("SYNO.API.Auth", "login", 7)
    val LOGOUT = ApiCall("SYNO.API.Auth", "logout", 7)

    fun timeline(space: Space) = ApiCall("${space.apiPrefix}.Browse.Timeline", "get", 6)
    fun itemList(space: Space) = ApiCall("${space.apiPrefix}.Browse.Item", "list", 7)
    fun itemCount(space: Space) = ApiCall("${space.apiPrefix}.Browse.Item", "count", 7)
    fun thumbnail(space: Space) = ApiCall("${space.apiPrefix}.Thumbnail", "get", 2)
    fun download(space: Space) = ApiCall("${space.apiPrefix}.Download", "download", 2)

    val all: Set<ApiCall> = buildSet {
        add(API_INFO)
        add(LOGIN)
        add(LOGOUT)
        for (space in Space.entries) {
            add(timeline(space))
            add(itemList(space))
            add(itemCount(space))
            add(thumbnail(space))
            add(download(space))
        }
    }

    fun require(call: ApiCall) {
        if (call !in all) throw DisallowedCallException(call)
    }
}

class DisallowedCallException(val call: ApiCall) :
    IllegalArgumentException("Refused: ${call.name} is not on the allowlist")

package com.hawwwran.photosonthisday.update

/**
 * A frozen update-check result. [isNewer] is the raw release-vs-installed comparison; the
 * "skip this version" filter is applied later in the view model. [stale] means the last network
 * attempt failed and this is a replay of the cached release. [apkSize] is GitHub's asset size, 0
 * when unknown; a complete download of that size is reused instead of fetched again.
 */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val apkUrl: String,
    val apkSize: Long,
    val releaseNotes: String,
    val isNewer: Boolean,
    val stale: Boolean,
)

/** What a version check found. */
sealed interface CheckOutcome {
    /** GitHub, or the cache, named a release; [UpdateInfo.isNewer] says whether it matters. */
    data class Found(val info: UpdateInfo) : CheckOutcome

    /** GitHub answered and no `v*` release carries an APK. Honestly "nothing to update to". */
    data object NoRelease : CheckOutcome

    /** No answer and nothing cached: nothing is known, and the screen must not claim "up to date". */
    data object Unreachable : CheckOutcome
}

/** The version check, as the view model sees it; [UpdateChecker] is the real one. */
fun interface UpdateChecking {
    suspend fun check(force: Boolean): CheckOutcome
}

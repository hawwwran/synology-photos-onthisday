package com.hawwwran.photosonthisday.update

/**
 * A frozen update-check result. [isNewer] is the raw release-vs-installed comparison; the
 * "skip this version" filter is applied later in the view model. [stale] means the last network
 * attempt failed and this is a replay of the cached release.
 */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val apkUrl: String,
    val releaseNotes: String,
    val isNewer: Boolean,
    val stale: Boolean,
)

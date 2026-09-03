package com.hawwwran.photosonthisday.ui

import java.util.Locale

/**
 * Bytes for the screen: binary units, one decimal, the device locale's separator. The one rule for
 * the cache size, the download progress and the info sheet. A non-positive count is the
 * placeholder dash, which the download progress shows before `Content-Length` is known.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

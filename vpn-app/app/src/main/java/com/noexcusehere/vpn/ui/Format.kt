package com.noexcusehere.vpn.ui

import java.util.Locale

/** 1536 -> "1.50 KB". Binary units, three significant figures. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val pattern = when {
        value >= 100 -> "%.0f %s"
        value >= 10 -> "%.1f %s"
        else -> "%.2f %s"
    }
    return String.format(Locale.US, pattern, value, units[unit])
}

/** 3725 -> "1:02:05"; under an hour -> "02:05". */
fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

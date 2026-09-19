package app.nester.ui.common

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

fun formatCount(count: Long): String = "%,d".format(count)

fun formatRelativeTime(epochSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochSeconds <= 0) return "never"
    val deltaMs = nowMs - epochSeconds * 1000
    val minutes = deltaMs / 60_000
    return when {
        deltaMs < 0 -> "never"
        deltaMs < 60_000 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond > 0 -> "${formatSize(bytesPerSecond)}/s"
    else -> "—"
}

fun formatEta(seconds: Long): String = when {
    seconds < 0 -> "—"
    seconds < 60 -> "<1m"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun formatDuration(seconds: Long): String = when {
    seconds < 0 -> "—"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

package app.nester.api

import java.io.File

const val PART_MAX_AGE_MS: Long = 24 * 60 * 60 * 1000

private const val PART_SUFFIX = ".part"

/// Deletes orphaned `.part` leftovers older than `maxAgeMs`, recursively under
/// `root`. Returns the count deleted. Files with an unreadable timestamp
/// (`lastModified == 0`) are left alone.
fun sweepStalePartFiles(root: File, nowMillis: Long, maxAgeMs: Long = PART_MAX_AGE_MS): Int {
    if (!root.isDirectory) return 0
    var deleted = 0
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        for (child in stack.removeLast().listFiles().orEmpty()) {
            if (child.isDirectory) {
                stack.addLast(child)
                continue
            }
            val mtime = child.lastModified()
            if (child.name.endsWith(PART_SUFFIX) && mtime > 0 && mtime < nowMillis - maxAgeMs && child.delete()) {
                deleted++
            }
        }
    }
    return deleted
}

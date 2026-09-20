package app.nester.backup

enum class ItemStatus { QUEUED, UPLOADING, DONE, CONFLICT, FAILED }

enum class BackupPhase { RUNNING, PAUSED, DONE }

data class BackupFailure(val itemId: String, val message: String)

data class BackupRun(
    val phase: BackupPhase,
    val planIds: List<String>,
    val sentBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val startedMs: Long,
    val endedMs: Long? = null,
)

class TransferRateTracker(private val maxSamples: Int = 10) {
    private data class Sample(val atMs: Long, val bytes: Long)
    private val samples = ArrayDeque<Sample>()

    fun record(nowMs: Long, cumulativeBytes: Long) {
        samples.addLast(Sample(nowMs, cumulativeBytes))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0
        val first = samples.first()
        val last = samples.last()
        val dtMs = last.atMs - first.atMs
        val dBytes = last.bytes - first.bytes
        if (dtMs <= 0 || dBytes <= 0) return 0
        return (dBytes * 1000) / dtMs
    }

    fun reset() = samples.clear()
}

fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long? =
    if (bytesPerSecond <= 0 || remainingBytes <= 0) null else remainingBytes / bytesPerSecond

fun failurePrefix(message: String): String {
    val prefix = message.substringBefore(':').trim()
    return prefix.ifEmpty { message.trim() }
}

data class FailureGroup(val prefix: String, val count: Int)

fun groupFailures(messages: List<String>): List<FailureGroup> =
    messages.groupingBy { failurePrefix(it) }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { FailureGroup(it.key, it.value) }

fun notBackedUpSummaryText(count: Long, bytes: Long): String =
    "Not backed up: %,d items (%s)".format(count, app.nester.ui.common.formatSize(bytes))

fun processedCount(done: Int, skipped: Int, failed: Int): Int = done + skipped + failed

fun backupEndStateHeadline(done: Int, planned: Int, skipped: Int, failed: Int): String =
    buildString {
        append("Finished - $done of $planned backed up")
        if (skipped > 0) append(" · $skipped skipped")
        if (failed > 0) append(" · $failed failed")
    }

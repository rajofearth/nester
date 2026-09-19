package app.nester.backup

data class MediaItem(
    val mediaStoreId: String,
    val displayName: String,
    val relativeDir: String,
    val size: Long,
    val dateModifiedS: Long,
    val isVideo: Boolean,
    val contentUri: String,
)

data class BackupPlanItem(val item: MediaItem, val remotePath: String)

data class BackupRun(
    val totalFiles: Int,
    val doneFiles: Int,
    val sentBytes: Long,
    val totalBytes: Long,
)

fun sanitizeName(name: String): String = name.replace(Regex("[/\\\\\\u0000]"), "_")

fun buildRemotePath(relativeDir: String, displayName: String): String =
    (relativeDir.split('/') + displayName.split('/'))
        .map { sanitizeName(it) }
        .filter { it.isNotEmpty() && it != ".." }
        .joinToString("/")

fun planBackup(all: List<MediaItem>, uploaded: Map<String, Long>): List<BackupPlanItem> =
    all.filter { it.mediaStoreId !in uploaded }
        .map { BackupPlanItem(item = it, remotePath = buildRemotePath(it.relativeDir, it.displayName)) }

package app.nester.gallery

import app.nester.store.EntryRecord
import app.nester.ui.common.extension

enum class MediaKind { PHOTO, VIDEO, FILE }

data class GalleryViews(
    val photos: List<EntryRecord>,
    val videos: List<EntryRecord>,
    val files: List<EntryRecord>,
)

fun classify(path: String): MediaKind = when (extension(path).lowercase()) {
    in imageExtensions -> MediaKind.PHOTO
    in videoExtensions -> MediaKind.VIDEO
    else -> MediaKind.FILE
}

fun filterByDir(entries: List<EntryRecord>, dir: String?): List<EntryRecord> =
    if (dir.isNullOrEmpty()) entries else entries.filter { it.path.startsWith("$dir/") }

fun dirPaths(entries: List<EntryRecord>): List<String> =
    entries.filter { it.kind == "dir" }.map { it.path }.distinct().sorted()

fun galleryViews(entries: List<EntryRecord>): GalleryViews {
    val byKind = entries.filter { it.kind != "dir" }.groupBy { classify(it.path) }
    val recency = compareByDescending<EntryRecord> { it.mtimeS }.thenBy { it.path }
    return GalleryViews(
        photos = (byKind[MediaKind.PHOTO] ?: emptyList()).sortedWith(recency),
        videos = (byKind[MediaKind.VIDEO] ?: emptyList()).sortedWith(recency),
        files = (byKind[MediaKind.FILE] ?: emptyList()).sortedBy { it.path },
    )
}

fun mimeFor(path: String): String = when (extension(path).lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "bmp" -> "image/bmp"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "3gp", "3gpp" -> "video/3gpp"
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    else -> "*/*"
}

private val imageExtensions =
    setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

private val videoExtensions =
    setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "3gpp")

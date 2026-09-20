package app.nester.data

import android.content.Context
import java.io.File

data class DownloadState(
    val active: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
)

fun localDownloadFile(context: Context, folderId: String, path: String): File =
    File(context.getExternalFilesDir(null), "nester/$folderId/$path")

fun purgeDownloaded(context: Context, folderId: String, path: String) {
    val f = localDownloadFile(context, folderId, path)
    f.delete()
    File(f.absolutePath + ".part").delete()
}

fun downloadedPaths(context: Context, folderId: String, entries: List<String>): Set<String> =
    entries.filter { path ->
        localDownloadFile(context, folderId, path).exists()
    }.toSet()

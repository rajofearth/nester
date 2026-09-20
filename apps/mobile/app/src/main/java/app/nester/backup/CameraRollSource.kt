package app.nester.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object CameraRollSource {

    private const val MAX_ITEMS = 5000

    fun query(context: Context, includeVideos: Boolean = true): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        items += queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        if (includeVideos) {
            items += queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        }
        return items.sortedByDescending { it.dateModifiedS }.take(MAX_ITEMS)
    }

    private fun queryCollection(context: Context, collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        var dataCol: String? = null
        var pathCol: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pathCol = MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            dataCol = MediaStore.MediaColumns.DATA
        }
        pathCol?.let { projection.add(it) }
        dataCol?.let { projection.add(it) }

        val items = mutableListOf<MediaItem>()
        context.contentResolver.query(collection, projection.toTypedArray(), null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            ?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mtimeC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathIdx = pathCol?.let { c.getColumnIndex(it) } ?: -1
                val dataIdx = dataCol?.let { c.getColumnIndex(it) } ?: -1
                while (c.moveToNext()) {
                    val id = c.getLong(idC)
                    val name = c.getString(nameC) ?: "IMG_$id"
                    val dir = when {
                        pathIdx >= 0 -> c.getString(pathIdx).orEmpty().trim('/')
                        dataIdx >= 0 -> {
                            val dataPath = c.getString(dataIdx).orEmpty()
                            val root = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
                            dataPath.removePrefix("$root/").substringBeforeLast('/')
                        }
                        else -> ""
                    }
                    val contentUri = collection.buildUpon().appendPath(id.toString()).build().toString()
                    items += MediaItem(
                        mediaStoreId = id.toString(),
                        displayName = name,
                        relativeDir = dir,
                        size = c.getLong(sizeC),
                        dateModifiedS = c.getLong(mtimeC),
                        isVideo = isVideo,
                        contentUri = contentUri,
                    )
                }
            }
        return items
    }
}

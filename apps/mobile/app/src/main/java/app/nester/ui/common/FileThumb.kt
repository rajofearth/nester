package app.nester.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.memory.MemoryCache
import java.io.File

@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache(MemoryCache.Builder(context).maxSizePercent(0.30).build())
            .build()
    }
}

@Composable
fun LocalFileThumb(localFile: File?, modifier: Modifier = Modifier) {
    val imageLoader = rememberMediaImageLoader()
    if (localFile != null && localFile.exists()) {
        AsyncImage(
            model = localFile,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        FileTypeTile(extension(localFile?.name ?: ""), modifier)
    }
}

@Composable
fun FileTypeTile(extension: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(6.dp),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = extension.uppercase().ifEmpty { "FILE" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun extension(path: String?): String {
    if (path == null) return ""
    val name = path.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1) else ""
}

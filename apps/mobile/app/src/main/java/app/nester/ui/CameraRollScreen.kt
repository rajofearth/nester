package app.nester.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nester.api.ConflictException
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.backup.BackupRun
import app.nester.backup.CameraRollSource
import app.nester.backup.MediaItem
import app.nester.backup.planBackup
import app.nester.store.NesterStore
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraRollScreen(store: NesterStore, folder: FolderDto, onBack: () -> Unit) {
    val context = LocalContext.current
    val pairing = store.loadPairing()
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var uploaded by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(mediaReadPermission()) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var runState by remember { mutableStateOf<BackupRun?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        val loaded = CameraRollSource.query(context)
        val fs = store.loadFolder(folder.id)
        uploaded = fs.uploadedMedia
        selected = loaded.map { it.mediaStoreId }.filter { it !in fs.uploadedMedia }.toSet()
        items = loaded
    }

    val videoPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else null
    val videoLauncher = videoPermission?.let {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load()
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted && videoLauncher != null) {
            videoLauncher.launch(videoPermission)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) load()
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context).components { add(VideoFrameDecoder.Factory()) }.build()
    }

    fun runBackup() {
        val p = pairing ?: return
        val plan = planBackup(items ?: emptyList(), uploaded)
        if (plan.isEmpty()) return
        val api = NesterApi(NesterApi.ApiConfig(p.host, p.port))
        val totalBytes = plan.sumOf { it.item.size }
        running = true
        summary = null
        error = null
        var uploadedCount = 0
        var skipped = 0
        var failed = 0
        var sentBefore = 0L
        scope.launch {
            for (pi in plan) {
                try {
                    withContext(Dispatchers.IO) {
                        if (pi.item.size <= 0) {
                            val tmp = materializeTemp(context, pi.item)
                            try {
                                api.upload(
                                    token = p.token,
                                    folderId = folder.id,
                                    path = pi.remotePath,
                                    file = tmp,
                                    mtimeS = pi.item.dateModifiedS,
                                )
                            } finally {
                                tmp.delete()
                            }
                        } else {
                            api.uploadStream(
                                token = p.token,
                                folderId = folder.id,
                                path = pi.remotePath,
                                openStream = {
                                    context.contentResolver.openInputStream(Uri.parse(pi.item.contentUri))
                                        ?: throw IOException("cannot open ${pi.item.displayName}")
                                },
                                size = pi.item.size,
                                mtimeS = pi.item.dateModifiedS,
                            ) { prog ->
                                runState = BackupRun(plan.size, uploadedCount, sentBefore + prog.sentBytes, totalBytes)
                            }
                        }
                    }
                    uploadedCount++
                    val now = System.currentTimeMillis()
                    uploaded = uploaded + (pi.item.mediaStoreId to now)
                    selected = selected - pi.item.mediaStoreId
                    val fs = withContext(Dispatchers.IO) { store.loadFolder(folder.id) }
                    withContext(Dispatchers.IO) {
                        store.saveFolder(folder.id, fs.copy(uploadedMedia = fs.uploadedMedia + (pi.item.mediaStoreId to now)))
                    }
                } catch (e: ConflictException) {
                    skipped++
                } catch (e: Exception) {
                    failed++
                    error = "Last item failed: ${e.message}"
                } finally {
                    sentBefore += pi.item.size
                }
                runState = BackupRun(plan.size, uploadedCount, sentBefore, totalBytes)
            }
            running = false
            summary = buildString {
                append("Backed up $uploadedCount of ${plan.size}")
                if (skipped > 0) append(". $skipped skipped: host had newer copies")
                if (failed > 0) append(". $failed failed")
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Back up camera roll") }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasPermission -> Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Camera roll backup needs access to your photos and videos.")
                    OutlinedButton(
                        onClick = { imageLauncher.launch(mediaReadPermission()) },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Grant access") }
                }
                items == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> {
                    val list = items ?: emptyList()
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onBack) { Text("Back") }
                            val pending = selected.count { it !in uploaded }
                            Text("$pending to back up", style = MaterialTheme.typography.bodyMedium)
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                        summary?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                        runState?.let { r ->
                            val frac = if (r.totalBytes > 0) r.sentBytes.toFloat() / r.totalBytes else 0f
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(
                                    "Uploading ${r.doneFiles + 1}/${r.totalFiles} (${formatSize(r.sentBytes)} of ${formatSize(r.totalBytes)})",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(list, key = { it.mediaStoreId }) { item ->
                                val isUploaded = item.mediaStoreId in uploaded
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(
                                        model = item.contentUri,
                                        contentDescription = null,
                                        imageLoader = imageLoader,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Text(
                                            "${formatSize(item.size)} - ${item.relativeDir.ifEmpty { "/" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (isUploaded) {
                                        Text("Uploaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Checkbox(
                                            checked = item.mediaStoreId in selected,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) selected + item.mediaStoreId else selected - item.mediaStoreId
                                            },
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        Button(
                            onClick = { confirm = true },
                            enabled = !running && selected.any { it !in uploaded },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        ) { Text(if (running) "Backing up..." else "Back up now") }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Back up ${selected.count { it !in uploaded }} items?") },
            text = { Text("Selected photos and videos will be uploaded to \"${folder.label}\" on the host.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    runBackup()
                }) { Text("Back up now") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun materializeTemp(context: Context, item: MediaItem): File {
    val tmp = File(context.cacheDir, "upload_${item.mediaStoreId}_${item.displayName}")
    val src = context.contentResolver.openInputStream(Uri.parse(item.contentUri))
        ?: throw IOException("cannot open ${item.displayName}")
    src.use { input ->
        tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
    }
    return tmp
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

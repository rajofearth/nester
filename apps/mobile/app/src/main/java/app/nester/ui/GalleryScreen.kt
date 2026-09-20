@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package app.nester.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.nester.api.FolderDto
import app.nester.api.IndexResetException
import app.nester.api.NesterApi
import app.nester.gallery.GALLERY_PAGE_SIZE
import app.nester.gallery.GalleryViews
import app.nester.gallery.ListPagingSource
import app.nester.gallery.classify
import app.nester.gallery.dirPaths
import app.nester.gallery.filterByDir
import app.nester.gallery.galleryViews
import app.nester.gallery.mimeFor
import app.nester.store.EntryRecord
import app.nester.store.FolderState
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import app.nester.ui.common.EmptyState
import app.nester.ui.common.FolderStatsLine
import app.nester.ui.common.InlineErrorRow
import app.nester.ui.common.extension
import app.nester.ui.common.formatSize
import app.nester.ui.common.rememberMediaImageLoader
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GalleryTab { Photos, Videos, Files }

data class DownloadState(
    val active: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
)

fun localDownloadFile(context: android.content.Context, folderId: String, path: String): File =
    File(context.getExternalFilesDir(null), "nester/$folderId/$path")

fun purgeDownloaded(context: android.content.Context, folderId: String, path: String) {
    val f = localDownloadFile(context, folderId, path)
    f.delete()
    File(f.absolutePath + ".part").delete()
}

@Composable
fun GalleryScreen(
    store: NesterStore,
    folder: FolderDto,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = store.loadPairing()
    var folderState by remember { mutableStateOf<FolderState>(store.loadFolder(folder.id)) }
    var pulling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<EntryRecord?>(null) }
    var pendingActions by remember { mutableStateOf<EntryRecord?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var activeFilter by remember { mutableStateOf<String?>(null) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(GalleryTab.Photos) }
    val downloadStates = remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var loadedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun applyPull(p: StoredPairing, since: Long) {
        val fresh = withContext(Dispatchers.IO) {
            NesterApi(NesterApi.ApiConfig(p.host, p.port)).entries(p.token, folder.id, since)
        }
        val byPath = folderState.entries.associateBy { it.path }.toMutableMap()
        var maxSeq = folderState.lastSequence
        for (e in fresh) {
            if (e.sequence > maxSeq) maxSeq = e.sequence
            if (e.deleted) {
                byPath.remove(e.path)
                purgeDownloaded(context, folder.id, e.path)
            } else {
                byPath[e.path] = EntryRecord(
                    path = e.path,
                    kind = e.kind,
                    size = e.size,
                    mtimeS = e.mtimeS,
                    deleted = false,
                    hash = e.hash,
                    sequence = e.sequence,
                )
            }
        }
        val newState = FolderState(lastSequence = maxSeq, entries = byPath.values.sortedBy { it.path })
        store.saveFolder(folder.id, newState)
        folderState = newState
    }

    fun pull(sinceOverride: Long? = null) {
        val p = pairing ?: return
        pulling = true
        scope.launch {
            try {
                val since = sinceOverride ?: folderState.lastSequence
                try {
                    applyPull(p, since)
                } catch (e: IndexResetException) {
                    val empty = folderState.copy(lastSequence = 0, entries = emptyList())
                    store.saveFolder(folder.id, empty)
                    folderState = empty
                    applyPull(p, 0)
                }
                error = null
            } catch (e: Exception) {
                error = "Pull failed: ${e.message}"
            } finally {
                pulling = false
            }
        }
    }

    LaunchedEffect(folder.id, hostState == HostState.Online) {
        pull(if (loadedOnce) null else 0)
        loadedOnce = true
    }

    fun deleteFile(entry: EntryRecord) {
        val p = pairing ?: return
        deleting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NesterApi(NesterApi.ApiConfig(p.host, p.port)).deleteFile(p.token, folder.id, entry.path)
                }
                purgeDownloaded(context, folder.id, entry.path)
                val remaining = folderState.entries.filterNot { it.path == entry.path }
                val newState = folderState.copy(entries = remaining)
                store.saveFolder(folder.id, newState)
                folderState = newState
            } catch (e: Exception) {
                error = "Delete failed: ${e.message}"
            } finally {
                deleting = false
            }
        }
    }

    fun openFile(entry: EntryRecord, localFile: File) {
        val uri = FileProvider.getUriForFile(context, "app.nester.fileprovider", localFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(entry.path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun tapEntry(entry: EntryRecord) {
        val p = pairing ?: return
        val localFile = localDownloadFile(context, folder.id, entry.path)
        if (localFile.exists()) {
            openFile(entry, localFile)
            return
        }
        val state = downloadStates.value[entry.path] ?: DownloadState()
        if (state.active) return
        downloadStates.value = downloadStates.value + (entry.path to DownloadState(active = true))
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NesterApi(NesterApi.ApiConfig(p.host, p.port)).download(
                        token = p.token,
                        folderId = folder.id,
                        path = entry.path,
                        destFile = localFile,
                        sizeBytes = entry.size,
                        mtimeS = entry.mtimeS,
                    ) { progress ->
                        val total = progress.totalBytes
                        val frac = if (total > 0) progress.downloadedBytes.toFloat() / total else 0f
                        downloadStates.value =
                            downloadStates.value + (entry.path to DownloadState(active = true, progress = frac))
                    }
                }
                downloadStates.value = downloadStates.value + (entry.path to DownloadState(done = true))
                openFile(entry, localFile)
            } catch (e: Exception) {
                downloadStates.value = downloadStates.value + (entry.path to DownloadState(error = e.message))
            }
        }
    }

    val files = folderState.entries.filter { it.kind != "dir" }
    val filtered = filterByDir(files, activeFilter)
    val views = galleryViews(filtered)
    val isEmpty = filtered.isEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        HostStatusStrip(hostState, hostLabel, onRetryHost)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.m),
            ) {
                Text(folder.label, style = MaterialTheme.typography.titleLarge)
                FolderStatsLine(folder.stats)
            }
            IconButton(onClick = { showFilterDialog = true }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Browse folders")
            }
        }
        activeFilter?.let { dir ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = Spacing.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = Spacing.s)
                            .size(14.dp),
                    )
                    Text(
                        "/$dir",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = Spacing.xs)
                            .weight(1f),
                    )
                    TextButton(onClick = { activeFilter = null }) { Text("Clear") }
                }
            }
        }
        androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab.ordinal) {
            GalleryTab.entries.forEach { t ->
                val count = when (t) {
                    GalleryTab.Photos -> views.photos.size
                    GalleryTab.Videos -> views.videos.size
                    GalleryTab.Files -> views.files.size
                }
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text("${t.name} $count") },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = { pull() },
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                error?.let { InlineErrorRow(it, onRetry = { pull() }) }
                when {
                    isEmpty && hostState == HostState.Offline && error != null -> EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "Connect to the host to see this folder",
                        subtitle = "The host is unreachable and this folder has no cached copy yet.",
                    )
                    isEmpty && error != null -> EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "Could not reach the host",
                        subtitle = "Pull failed. Tap retry when the host is reachable.",
                    )
                    isEmpty -> EmptyState(
                        icon = Icons.Filled.Info,
                        title = "Nothing in this view yet",
                        subtitle = "Files added to \"${folder.root}\" will appear here after a pull.",
                    )
                    else -> GalleryTabContent(
                        tab = tab,
                        views = views,
                        folder = folder,
                        downloadStates = downloadStates,
                        deleting = deleting,
                        onTap = ::tapEntry,
                        onLongPress = { pendingActions = it },
                        onDeleteClick = { pendingDelete = it },
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        val dirs = dirPaths(folderState.entries)
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Show one folder") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "All files",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activeFilter = null; showFilterDialog = false }
                                .padding(vertical = Spacing.s),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(dirs) { dir ->
                        Text(
                            "/$dir",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activeFilter = dir; showFilterDialog = false }
                                .padding(vertical = Spacing.xs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) { Text("Done") }
            },
        )
    }

    pendingActions?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingActions = null },
            title = { Text(shortName(target.path)) },
            text = {
                Column {
                    Text(
                        "Open",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingActions = null; tapEntry(target) }
                            .padding(vertical = Spacing.s),
                    )
                    Text(
                        "Delete",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pendingActions = null; pendingDelete = target }
                            .padding(vertical = Spacing.s),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingActions = null }) { Text("Close") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text("Delete \"${shortName(target.path)}\"?") },
            text = { Text("This deletes the file from the host and every paired device, including this phone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = target
                        pendingDelete = null
                        deleteFile(t)
                    },
                    enabled = !deleting,
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }, enabled = !deleting) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun GalleryTabContent(
    tab: GalleryTab,
    views: GalleryViews,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    deleting: Boolean,
    onTap: (EntryRecord) -> Unit,
    onLongPress: (EntryRecord) -> Unit,
    onDeleteClick: (EntryRecord) -> Unit,
) {
    when (tab) {
        GalleryTab.Photos -> if (views.photos.isEmpty()) EmptyState(
            icon = Icons.Filled.Info,
            title = "No photos yet",
            subtitle = "Images in this folder show up here as a grid.",
        ) else MediaGrid(
            entries = views.photos,
            columns = 4,
            isVideo = false,
            folder = folder,
            downloadStates = downloadStates,
            onTap = onTap,
            onLongPress = onLongPress,
        )
        GalleryTab.Videos -> if (views.videos.isEmpty()) EmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "No videos yet",
            subtitle = "Videos in this folder show up here as a grid.",
        ) else MediaGrid(
            entries = views.videos,
            columns = 3,
            isVideo = true,
            folder = folder,
            downloadStates = downloadStates,
            onTap = onTap,
            onLongPress = onLongPress,
        )
        GalleryTab.Files -> if (views.files.isEmpty()) EmptyState(
            icon = Icons.Filled.Info,
            title = "No other files",
            subtitle = "Documents and anything that is not a photo or video lands here.",
        ) else FilesList(
            entries = views.files,
            folder = folder,
            downloadStates = downloadStates,
            deleting = deleting,
            onTap = onTap,
            onDeleteClick = onDeleteClick,
        )
    }
}

@Composable
private fun rememberPaged(items: List<EntryRecord>): LazyPagingItems<EntryRecord> {
    val flow = remember(items) {
        Pager(PagingConfig(pageSize = GALLERY_PAGE_SIZE, enablePlaceholders = false)) {
            ListPagingSource(items)
        }.flow
    }
    return flow.collectAsLazyPagingItems()
}

@Composable
private fun MediaGrid(
    entries: List<EntryRecord>,
    columns: Int,
    isVideo: Boolean,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    onTap: (EntryRecord) -> Unit,
    onLongPress: (EntryRecord) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = rememberMediaImageLoader()
    val paged = rememberPaged(entries)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
    ) {
        items(
            count = paged.itemCount,
            key = paged.itemKey { it.path },
            contentType = { "media" },
        ) { index ->
            val entry = paged[index] ?: return@items
            val local = localDownloadFile(context, folder.id, entry.path)
            val state = downloadStates.value[entry.path] ?: DownloadState()
            val downloaded = local.exists()
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .padding(2.dp)
                    .combinedClickable(
                        onClick = { onTap(entry) },
                        onLongClick = { onLongPress(entry) },
                    ),
            ) {
                if (downloaded) {
                    coil.compose.AsyncImage(
                        model = local,
                        contentDescription = shortName(entry.path),
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    NeutralTile(
                        isVideo = isVideo,
                        label = extension(entry.path),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isVideo) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    )
                }
                if (state.active) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(2.dp),
                    )
                }
                state.error?.let {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Download failed: $it",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NeutralTile(isVideo: Boolean, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isVideo) Icons.Filled.PlayArrow else Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            if (label.isNotEmpty()) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilesList(
    entries: List<EntryRecord>,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    deleting: Boolean,
    onTap: (EntryRecord) -> Unit,
    onDeleteClick: (EntryRecord) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.path }, contentType = { "file" }) { entry ->
            val state = downloadStates.value[entry.path] ?: DownloadState()
            val local = localDownloadFile(context, folder.id, entry.path)
            val downloaded = local.exists()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap(entry) }
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
            ) {
                app.nester.ui.common.FileTypeTile(
                    extension(entry.path),
                    modifier = Modifier.size(40.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.s),
                ) {
                    Text(shortName(entry.path), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        "${formatSize(entry.size)} · ${entry.path.substringBeforeLast('/').ifEmpty { "/" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    state.active -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    state.error != null -> Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Download failed: ${state.error}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    downloaded -> Icon(
                        Icons.Filled.Done,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onDeleteClick(entry) }, enabled = !deleting) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${shortName(entry.path)}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

private fun shortName(path: String): String = path.substringAfterLast('/')

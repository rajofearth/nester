@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package app.nester.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.nester.api.FolderDto
import app.nester.api.IndexResetException
import app.nester.api.NesterApi
import app.nester.backup.BackupRunState
import app.nester.backup.CameraRollSource
import app.nester.backup.ItemStatus
import app.nester.backup.buildRemotePath
import app.nester.backup.planBackup
import app.nester.data.DownloadState
import app.nester.data.SettingsStore
import app.nester.data.localDownloadFile
import app.nester.data.purgeDownloaded
import app.nester.gallery.GALLERY_PAGE_SIZE
import app.nester.gallery.GalleryViews
import app.nester.gallery.ListPagingSource
import app.nester.gallery.galleryViews
import app.nester.gallery.mimeFor
import app.nester.store.EntryRecord
import app.nester.store.FolderState
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import app.nester.ui.common.ChipState
import app.nester.ui.common.EmptyPane
import app.nester.ui.common.EmptyPaneBounded
import app.nester.ui.common.FileTypeTile
import app.nester.ui.common.StackedItem
import app.nester.ui.common.StatusChip
import app.nester.ui.common.extension
import app.nester.ui.common.formatCompact
import app.nester.ui.common.formatRelativeTime
import app.nester.ui.common.formatSize
import app.nester.ui.common.pressScale
import app.nester.ui.common.rememberMediaImageLoader
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import coil.ImageLoader
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

private data class CameraFolderStats(
    val pendingPaths: Set<String> = emptySet(),
    val backedPaths: Set<String> = emptySet(),
    val pendingIds: Map<String, String> = emptyMap(),
    val pendingCount: Int = 0,
    val backedCount: Int = 0,
    val pendingBytes: Long = 0,
)

enum class FolderTab { Photos, Videos, Files }

@Composable
fun FolderScreen(
    store: NesterStore,
    folder: FolderDto,
    settings: SettingsStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onBack: () -> Unit,
    onOpenCameraBackup: (FolderDto) -> Unit,
) {
    val context = LocalContext.current
    val pairing = store.loadPairing()
    var folderState by remember { mutableStateOf<FolderState>(store.loadFolder(folder.id)) }
    var pulling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<EntryRecord?>(null) }
    var sheetEntry by remember { mutableStateOf<EntryRecord?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(FolderTab.Photos) }
    val downloadStates = remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var loadedOnce by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cameraFolderId by settings.cameraFolderId.collectAsState(initial = null)
    val includeVideos by settings.includeVideos.collectAsState(initial = true)
    val isCameraTarget = cameraFolderId == folder.id
    var cameraStats by remember { mutableStateOf<CameraFolderStats?>(null) }
    var hasMediaPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(mediaReadPermission()) == PackageManager.PERMISSION_GRANTED,
        )
    }

    LaunchedEffect(folder.id, hasMediaPermission, isCameraTarget, includeVideos) {
        if (!isCameraTarget || !hasMediaPermission) {
            cameraStats = null
            return@LaunchedEffect
        }
        val stats = withContext(Dispatchers.IO) {
            val items = CameraRollSource.query(context, includeVideos)
            val uploaded = store.loadFolder(folder.id).uploadedMedia
            val plan = planBackup(items, uploaded)
            CameraFolderStats(
                pendingPaths = plan.map { it.remotePath }.toSet(),
                backedPaths = items.filter { it.mediaStoreId in uploaded }
                    .map { buildRemotePath(it.relativeDir, it.displayName) }.toSet(),
                pendingIds = plan.associate { it.remotePath to it.item.mediaStoreId },
                pendingCount = plan.size,
                backedCount = items.count { it.mediaStoreId in uploaded },
                pendingBytes = plan.sumOf { it.item.size },
            )
        }
        cameraStats = stats
    }

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

    fun startDownload(entry: EntryRecord, autoOpen: Boolean) {
        val p = pairing ?: return
        val state = downloadStates.value[entry.path] ?: DownloadState()
        if (state.active) return
        val localFile = localDownloadFile(context, folder.id, entry.path)
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
                if (autoOpen) openFile(entry, localFile)
            } catch (e: Exception) {
                downloadStates.value = downloadStates.value + (entry.path to DownloadState(error = e.message))
            }
        }
    }

    fun tapEntry(entry: EntryRecord) {
        val localFile = localDownloadFile(context, folder.id, entry.path)
        if (localFile.exists()) {
            openFile(entry, localFile)
            return
        }
        sheetEntry = entry
    }

    val stats = folder.stats
    val cam = cameraStats
    val pendingItemsForButton = cam?.pendingCount ?: 0
    val summaryText = when {
        isCameraTarget && cam != null ->
            if (cam.pendingCount == 0) {
                "Synced - ${formatCompact(stats?.files?.toLong() ?: 0)} - ${formatSize(stats?.bytes ?: 0)}"
            } else {
                "Not synced - ${formatCompact(cam.pendingCount.toLong())} pending - " +
                    "${formatCompact(cam.backedCount.toLong())} backed up - ${formatSize(cam.pendingBytes)}"
            }
        stats != null && hostState == HostState.Online ->
            "Synced - ${formatCompact(stats.files.toLong())} - ${formatSize(stats.bytes)}"
        stats != null -> "Not synced - host offline"
        else -> "Checking..."
    }
    val lastChangeLine =
        if ((folder.stats?.lastChangeAt ?: 0) <= 0) {
            "No changes recorded yet"
        } else {
            "Last change ${formatRelativeTime(folder.stats?.lastChangeAt ?: 0)} on the host"
        }

    val files = folderState.entries.filter { it.kind != "dir" }
    val views = galleryViews(files)
    val isEmpty = files.isEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.s)) {
                Text(
                    "${folder.label} - Nester",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRetryHost, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Rounded.Public,
                    contentDescription = "Host connectivity",
                    tint = when (hostState) {
                        HostState.Online -> MaterialTheme.colorScheme.primary
                        HostState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                        HostState.Offline -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (isCameraTarget && pendingItemsForButton > 0) {
                    Button(
                        onClick = { onOpenCameraBackup(folder) },
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Icon(
                            Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "Back up $pendingItemsForButton",
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                } else if (!isCameraTarget) {
                    OutlinedButton(
                        onClick = { pull() },
                        modifier = Modifier.heightIn(min = 40.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Refresh", modifier = Modifier.padding(start = Spacing.s))
                    }
                }
            }
            Text(
                lastChangeLine,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        HostStatusStrip(hostState, hostLabel, onRetryHost)
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            FolderTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.name, style = MaterialTheme.typography.titleSmall) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = { pull() },
            modifier = Modifier.weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = Spacing.s, top = 2.dp, bottom = 2.dp),
                            )
                            TextButton(onClick = { pull() }) { Text("Retry") }
                        }
                    }
                }
                when {
                    isEmpty && hostState == HostState.Offline && error != null -> EmptyPane(
                        icon = Icons.Rounded.Warning,
                        title = "Connect to the host to see this folder",
                        subtitle = "The host is unreachable and this folder has no cached copy yet.",
                    )
                    isEmpty && error != null -> EmptyPane(
                        icon = Icons.Rounded.Warning,
                        title = "Could not reach the host",
                        subtitle = "Pull failed. Tap retry when the host is reachable.",
                    )
                    isEmpty -> EmptyPane(
                        icon = Icons.Rounded.Info,
                        title = "Nothing in this view yet",
                        subtitle = "Files added to \"${folder.root}\" will appear here after a pull.",
                    )
                    else -> FolderTabContent(
                        tab = tab,
                        views = views,
                        folder = folder,
                        downloadStates = downloadStates,
                        deleting = deleting,
                        cameraStats = if (isCameraTarget) cam else null,
                        statuses = BackupRunState.statuses,
                        onTap = ::tapEntry,
                        onLongPress = { sheetEntry = it },
                        onDeleteClick = { pendingDelete = it },
                    )
                }
            }
        }
    }

    sheetEntry?.let { target ->
        EntrySheet(
            entry = target,
            folder = folder,
            downloadStates = downloadStates,
            cameraStats = if (isCameraTarget) cam else null,
            isCameraTarget = isCameraTarget,
            onDismiss = { sheetEntry = null },
            onDownload = {
                val e = target
                sheetEntry = null
                startDownload(e, autoOpen = true)
            },
            onOpen = {
                val e = target
                sheetEntry = null
                val localFile = localDownloadFile(context, folder.id, e.path)
                if (localFile.exists()) openFile(e, localFile)
            },
            onDelete = {
                val e = target
                sheetEntry = null
                pendingDelete = e
            },
            onOpenCameraBackup = { sheetEntry = null; onOpenCameraBackup(folder) },
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
private fun EntrySheet(
    entry: EntryRecord,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    cameraStats: CameraFolderStats?,
    isCameraTarget: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onOpenCameraBackup: () -> Unit,
) {
    val context = LocalContext.current
    val state = downloadStates.value[entry.path] ?: DownloadState()
    val local = localDownloadFile(context, folder.id, entry.path)
    val downloaded = local.exists()
    val imageLoader = rememberMediaImageLoader()
    val pending = cameraStats != null && entry.path in cameraStats.pendingPaths
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                if (downloaded) {
                    AsyncImage(
                        model = local,
                        contentDescription = shortName(entry.path),
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    FileTypeTile(extension(entry.path), modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                shortName(entry.path),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = Spacing.m),
            )
            Text(
                "${formatSize(entry.size)} - ${entry.path.substringBeforeLast('/').ifEmpty { "/" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            when {
                state.active -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.m),
                ) {
                    Icon(Icons.Rounded.Downloading, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "Downloading ${(state.progress * 100).toInt()}%",
                        modifier = Modifier.padding(start = Spacing.s),
                    )
                }
                downloaded -> Button(
                    onClick = onOpen,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.m),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text("Open")
                }
                else -> Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.m),
                ) {
                    Icon(Icons.Rounded.Downloading, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Download", modifier = Modifier.padding(start = Spacing.s))
                }
            }
            if (isCameraTarget && pending && !state.active) {
                Button(
                    onClick = onOpenCameraBackup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.s),
                ) {
                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Back up this photo", modifier = Modifier.padding(start = Spacing.s))
                }
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.s),
            ) {
                Text("Delete")
            }
        }
    }
}

@Composable
private fun FolderTabContent(
    tab: FolderTab,
    views: GalleryViews,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    deleting: Boolean,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
    onTap: (EntryRecord) -> Unit,
    onLongPress: (EntryRecord) -> Unit,
    onDeleteClick: (EntryRecord) -> Unit,
) {
    when (tab) {
        FolderTab.Photos -> if (views.photos.isEmpty()) EmptyPaneBounded(
            icon = Icons.Rounded.Photo,
            title = "No photos yet",
            subtitle = "Images in this folder show up here as a grid.",
        ) else MediaGrid(
            entries = views.photos,
            isVideo = false,
            folder = folder,
            downloadStates = downloadStates,
            cameraStats = cameraStats,
            statuses = statuses,
            onTap = onTap,
            onLongPress = onLongPress,
        )
        FolderTab.Videos -> if (views.videos.isEmpty()) EmptyPaneBounded(
            icon = Icons.Rounded.PlayArrow,
            title = "No videos yet",
            subtitle = "Videos in this folder show up here as a grid.",
        ) else MediaGrid(
            entries = views.videos,
            isVideo = true,
            folder = folder,
            downloadStates = downloadStates,
            cameraStats = cameraStats,
            statuses = statuses,
            onTap = onTap,
            onLongPress = onLongPress,
        )
        FolderTab.Files -> if (views.files.isEmpty()) EmptyPaneBounded(
            icon = Icons.Rounded.Info,
            title = "No other files",
            subtitle = "Documents and anything that is not a photo or video lands here.",
        ) else FilesList(
            entries = views.files,
            folder = folder,
            downloadStates = downloadStates,
            cameraStats = cameraStats,
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
    isVideo: Boolean,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
    onTap: (EntryRecord) -> Unit,
    onLongPress: (EntryRecord) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = rememberMediaImageLoader()
    val paged = rememberPaged(entries)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
    ) {
        items(
            count = paged.itemCount,
            key = paged.itemKey { it.path },
            contentType = { "media" },
        ) { index ->
            val entry = paged[index] ?: return@items
            MediaCell(
                entry = entry,
                isVideo = isVideo,
                folder = folder,
                downloadStates = downloadStates,
                cameraStats = cameraStats,
                statuses = statuses,
                imageLoader = imageLoader,
                onTap = onTap,
                onLongPress = onLongPress,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaCell(
    entry: EntryRecord,
    isVideo: Boolean,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
    imageLoader: ImageLoader,
    onTap: (EntryRecord) -> Unit,
    onLongPress: (EntryRecord) -> Unit,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val local = localDownloadFile(context, folder.id, entry.path)
    val state = downloadStates.value[entry.path] ?: DownloadState()
    val downloaded = local.exists()
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(20.dp))
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onTap(entry) },
                onLongClick = { onLongPress(entry) },
            ),
    ) {
        if (downloaded) {
            AsyncImage(
                model = local,
                contentDescription = shortName(entry.path),
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.Photo,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    if (extension(entry.path).isNotEmpty()) {
                        Text(
                            extension(entry.path),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        StatusChip(
            label = chipLabel(entry, downloaded, state, cameraStats, statuses),
            state = chipState(entry, downloaded, state, cameraStats, statuses),
            leading = chipLeading(entry, downloaded, state, cameraStats, statuses),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        )
        if (state.active) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(2.dp),
            )
        }
    }
}

private fun shortName(path: String): String = path.substringAfterLast('/')

private fun chipLabel(
    entry: EntryRecord,
    downloaded: Boolean,
    state: DownloadState,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
): String = when {
    cameraStats != null && entry.path in cameraStats.pendingPaths -> "Not backed up"
    cameraStats != null && statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.UPLOADING -> "Syncing"
    cameraStats != null && statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.QUEUED -> "Syncing"
    cameraStats != null && statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.FAILED -> "Failed"
    cameraStats != null && statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.CONFLICT -> "Failed"
    cameraStats != null && entry.path in cameraStats.backedPaths -> "Synced"
    state.active -> "Syncing"
    state.error != null -> "Failed"
    downloaded -> "Synced"
    else -> "Not synced"
}

private fun chipState(
    entry: EntryRecord,
    downloaded: Boolean,
    state: DownloadState,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
): ChipState = when {
    cameraStats != null && entry.path in cameraStats.pendingPaths -> ChipState.Neutral
    cameraStats != null && (statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.UPLOADING || statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.QUEUED) -> ChipState.Progress
    cameraStats != null && (statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.FAILED || statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.CONFLICT) -> ChipState.Error
    cameraStats != null && entry.path in cameraStats.backedPaths -> ChipState.Selected
    state.active -> ChipState.Progress
    state.error != null -> ChipState.Error
    downloaded -> ChipState.Selected
    else -> ChipState.Neutral
}

private fun chipLeading(
    entry: EntryRecord,
    downloaded: Boolean,
    state: DownloadState,
    cameraStats: CameraFolderStats?,
    statuses: Map<String, ItemStatus>,
) = when {
    cameraStats != null && entry.path in cameraStats.pendingPaths -> Icons.Rounded.CloudUpload
    cameraStats != null && (statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.UPLOADING || statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.QUEUED) -> Icons.Rounded.Sync
    cameraStats != null && (statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.FAILED || statuses[cameraStats.pendingIds[entry.path]] == ItemStatus.CONFLICT) -> Icons.Rounded.SyncProblem
    cameraStats != null && entry.path in cameraStats.backedPaths -> Icons.Rounded.Check
    state.active -> Icons.Rounded.Sync
    state.error != null -> Icons.Rounded.SyncProblem
    downloaded -> Icons.Rounded.Check
    else -> Icons.Rounded.CloudDownload
}

@Composable
private fun FilesList(
    entries: List<EntryRecord>,
    folder: FolderDto,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    cameraStats: CameraFolderStats?,
    deleting: Boolean,
    onTap: (EntryRecord) -> Unit,
    onDeleteClick: (EntryRecord) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.s, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(entries, key = { it.path }, contentType = { "file" }) { entry ->
            val state = downloadStates.value[entry.path] ?: DownloadState()
            val local = localDownloadFile(context, folder.id, entry.path)
            val downloaded = local.exists()
            StackedItem(onClick = { onTap(entry) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                ) {
                    FileTypeTile(
                        extension(entry.path),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Spacing.s),
                    ) {
                        Text(shortName(entry.path), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            "${formatSize(entry.size)} - ${entry.path.substringBeforeLast('/').ifEmpty { "/" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusChip(
                        label = chipLabel(entry, downloaded, state, cameraStats, emptyMap()),
                        state = chipState(entry, downloaded, state, cameraStats, emptyMap()),
                        leading = chipLeading(entry, downloaded, state, cameraStats, emptyMap()),
                    )
                    IconButton(onClick = { onDeleteClick(entry) }, enabled = !deleting) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete ${shortName(entry.path)}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

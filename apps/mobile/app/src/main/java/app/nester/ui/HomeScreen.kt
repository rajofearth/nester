@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nester.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.PersonalInjury
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.backup.CameraRollSource
import app.nester.data.SettingsStore
import app.nester.store.NesterStore
import app.nester.ui.common.EmptyPane
import app.nester.ui.common.EmptyPaneBounded
import app.nester.ui.common.LeadingIconCircle
import app.nester.ui.common.NesterListItem
import app.nester.ui.common.StackedItem
import app.nester.ui.common.StackedListOuter
import app.nester.ui.common.formatCompact
import app.nester.ui.common.formatSize
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun HomeScreen(
    store: NesterStore,
    settings: SettingsStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onOpenFolder: (FolderDto) -> Unit,
    onOpenCameraBackup: (FolderDto) -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = remember { store.loadPairing() }
    var folders by remember { mutableStateOf<List<FolderDto>>(emptyList()) }
    var loadedAny by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    val cameraFolderId by settings.cameraFolderId.collectAsState(initial = null)
    var cameraCounts by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    LaunchedEffect(pairing, hostState == HostState.Online, refreshTick) {
        val p = pairing ?: return@LaunchedEffect
        if (hostState != HostState.Online && loadedAny) return@LaunchedEffect
        try {
            isRefreshing = true
            val fetched = withContext(Dispatchers.IO) {
                NesterApi(NesterApi.ApiConfig(p.host, p.port)).folders(p.token)
            }
            folders = fetched
            loadedAny = true
        } catch (_: Exception) {
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(cameraFolderId, folders) {
        cameraCounts = null
        val id = cameraFolderId ?: return@LaunchedEffect
        if (folders.none { it.id == id }) return@LaunchedEffect
        if (context.checkSelfPermission(mediaReadPermission()) != PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        val counts = withContext(Dispatchers.IO) {
            val items = CameraRollSource.query(context, true)
            val uploaded = store.loadFolder(id).uploadedMedia
            val pending = items.count { it.mediaStoreId !in uploaded }
            pending to (items.size - pending)
        }
        cameraCounts = counts
    }

    fun syncLine(folder: FolderDto): String {
        if (hostState == HostState.Offline) return "Offline - showing cached files"
        val stats = folder.stats ?: return "Checking..."
        val files = formatCompact(stats.files.toLong())
        val size = formatSize(stats.bytes)
        if (folder.id == cameraFolderId) {
            cameraCounts?.let { (pending, backed) ->
                return if (pending == 0) {
                    "Synced - $files - $size"
                } else {
                    "Not synced - ${formatCompact(pending.toLong())} pending - ${formatCompact(backed.toLong())} synced - $size"
                }
            }
        }
        return if (stats.state == "syncing") {
            "Syncing - $files files - $size"
        } else {
            "Synced - $files - $size"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(state = hostState, onRetryHost = onRetryHost)
        HostStatusStrip(state = hostState, hostLabel = hostLabel, onRetry = onRetryHost)
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshTick++ },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        !loadedAny -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        folders.isEmpty() && hostState == HostState.Offline -> EmptyPane(
                            icon = Icons.Rounded.CloudOff,
                            title = "Host unreachable",
                            subtitle = "Connect to the host to load folders",
                        )
                        folders.isEmpty() -> EmptyPaneBounded(
                            icon = Icons.Rounded.FolderOpen,
                            title = "No folders yet",
                            subtitle = "Add a folder on the host computer",
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = Spacing.m),
                        ) {
                            StackedListOuter {
                                folders.forEach { folder ->
                                    StackedItem(
                                        onClick = { onOpenFolder(folder) },
                                    ) {
                                        NesterListItem(
                                            title = folder.label,
                                            supporting = syncLine(folder),
                                            leading = { LeadingIconCircle(folderIconFor(folder.label)) },
                                            trailing = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (folder.id == cameraFolderId) {
                                                        IconButton(onClick = { onOpenCameraBackup(folder) }) {
                                                            Icon(
                                                                Icons.Rounded.Backup,
                                                                contentDescription = "Camera backup",
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                    Icon(
                                                        Icons.Rounded.ChevronRight,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = Spacing.m, bottom = Spacing.s),
                ) {
                    Icon(
                        Icons.Rounded.DriveFolderUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Add Folder")
                }
                TextButton(
                    onClick = onUnpair,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = Spacing.m, bottom = Spacing.s),
                ) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            modifier = Modifier.width(312.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Add a folder", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Folders are added on the host computer. Open nester on your computer, add the folder there, and it appears here after the next refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun folderIconFor(label: String): ImageVector {
    val l = label.lowercase()
    return when {
        "pic" in l || "photo" in l || "camera" in l || "dcim" in l -> Icons.Rounded.PhotoLibrary
        "doc" in l -> Icons.Rounded.LibraryBooks
        "person" in l -> Icons.Rounded.PersonalInjury
        else -> Icons.Rounded.Folder
    }
}

@Composable
private fun HomeTopBar(state: HostState, onRetryHost: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Nester",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.s),
        )
        IconButton(onClick = onRetryHost) {
            Icon(
                Icons.Rounded.Public,
                contentDescription = "Host connectivity",
                tint = when (state) {
                    HostState.Online -> MaterialTheme.colorScheme.primary
                    HostState.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
                    HostState.Offline -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

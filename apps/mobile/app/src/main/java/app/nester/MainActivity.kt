@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nester

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.nester.api.FolderDto
import app.nester.api.FolderStatsDto
import app.nester.api.IndexResetException
import app.nester.api.NesterApi
import app.nester.api.sweepStalePartFiles
import app.nester.pair.PairParseResult
import app.nester.pair.PairingParser
import app.nester.store.EntryRecord
import app.nester.store.FolderState
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import app.nester.ui.CameraRollScreen
import app.nester.ui.QrScanScreen
import app.nester.ui.common.LocalFileThumb
import app.nester.ui.common.formatCount
import app.nester.ui.common.formatRelativeTime
import app.nester.ui.common.formatSize
import app.nester.ui.status.HeartbeatEffect
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusHolder
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.NesterTheme
import app.nester.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun purgeDownloaded(context: Context, folderId: String, path: String) {
    val f = File(context.getExternalFilesDir(null), "nester/$folderId/$path")
    f.delete()
    File(f.absolutePath + ".part").delete()
}

sealed interface Screen {
    data object Pair : Screen
    data object Folders : Screen
    data class Entries(val folder: FolderDto) : Screen
    data class CameraRoll(val folder: FolderDto) : Screen
    data object Qr : Screen
}

data class DownloadState(
    val active: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val error: String? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NesterApp() }
    }
}

@Composable
fun NesterApp() {
    val context = LocalContext.current
    val store = remember { NesterStore(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Pair) }
    val hostStatus = remember { HostStatusHolder() }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (store.loadPairing() != null) screen = Screen.Folders
        withContext(Dispatchers.IO) {
            context.getExternalFilesDir(null)?.let {
                sweepStalePartFiles(File(it, "nester"), System.currentTimeMillis())
            }
        }
    }

    HeartbeatEffect(
        active = screen !is Screen.Pair && screen !is Screen.Qr,
        store = store,
        holder = hostStatus,
        retryTick = retryTick,
    )

    NesterTheme {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val s = screen) {
                    Screen.Pair -> PairScreen(
                        store = store,
                        onPaired = { screen = Screen.Folders },
                        onScanQr = { screen = Screen.Qr },
                    )
                    Screen.Qr -> QrScanScreen(
                        onResult = { raw ->
                            val result = PairingParser.parse(raw)
                            if (result is PairParseResult.Ok) {
                                store.savePairing(
                                    StoredPairing(
                                        host = result.config.host,
                                        port = result.config.port,
                                        token = result.config.token,
                                    ),
                                )
                                screen = Screen.Folders
                            }
                        },
                        onCancel = { screen = Screen.Pair },
                    )
                    Screen.Folders -> FoldersScreen(
                        store = store,
                        hostState = hostStatus.state,
                        hostLabel = store.loadPairing()?.host,
                        onRetryHost = { retryTick++ },
                        onOpenFolder = { screen = Screen.Entries(it) },
                        onBackupCamera = { screen = Screen.CameraRoll(it) },
                        onUnpair = {
                            store.clearPairing()
                            screen = Screen.Pair
                        },
                    )
                    is Screen.Entries -> EntriesScreen(
                        store = store,
                        folder = s.folder,
                        hostState = hostStatus.state,
                        hostLabel = store.loadPairing()?.host,
                        onRetryHost = { retryTick++ },
                        onBack = { screen = Screen.Folders },
                    )
                    is Screen.CameraRoll -> CameraRollScreen(
                        store = store,
                        folder = s.folder,
                        hostState = hostStatus.state,
                        hostLabel = store.loadPairing()?.host,
                        onRetryHost = { retryTick++ },
                        onBack = { screen = Screen.Folders },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, subtitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.s),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun SyncStateChip(state: String?) {
    when (state) {
        "syncing", "error" -> Surface(
            color = if (state == "error") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                if (state == "error") "Sync error" else "Syncing",
                style = MaterialTheme.typography.labelSmall,
                color = if (state == "error") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun FolderStatsLine(stats: FolderStatsDto?) {
    if (stats == null) return
    Text(
        "${formatCount(stats.files.toLong())} files · ${formatSize(stats.bytes)} · updated ${formatRelativeTime(stats.lastChangeAt)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InlineErrorRow(message: String, retryLabel: String = "Retry", onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.s, top = 2.dp, bottom = 2.dp, end = 2.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(retryLabel) }
        }
    }
}

@Composable
private fun PairScreen(
    store: NesterStore,
    onPaired: () -> Unit,
    onScanQr: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.m, vertical = Spacing.m),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text("Pair with host", style = MaterialTheme.typography.titleLarge)
        Text(
            "Enter the details shown on the host, or scan its QR code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host (IP)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        inputError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                inputError = null
                val hostTrim = host.trim()
                val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                val tokenTrim = token.trim()
                if (hostTrim.isEmpty()) {
                    inputError = "Enter the host IP shown on the host screen"
                    return@Button
                }
                if (portNum == null) {
                    inputError = "Port must be between 1 and 65535"
                    return@Button
                }
                if (tokenTrim.length < 16) {
                    inputError = "Token must be at least 16 characters"
                    return@Button
                }
                checking = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NesterApi(NesterApi.ApiConfig(hostTrim, portNum)).health()
                        }
                        store.savePairing(StoredPairing(hostTrim, portNum, tokenTrim))
                        onPaired()
                    } catch (e: Exception) {
                        inputError = "Pairing failed: host did not answer (${e.message ?: "network error"})"
                    } finally {
                        checking = false
                    }
                }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (checking) "Pairing..." else "Save and verify")
        }
        OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
            Text("Scan QR")
        }
    }
}

@Composable
private fun FoldersScreen(
    store: NesterStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onOpenFolder: (FolderDto) -> Unit,
    onBackupCamera: (FolderDto) -> Unit,
    onUnpair: () -> Unit,
) {
    var folders by remember { mutableStateOf<List<FolderDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    val pairing = store.loadPairing()

    LaunchedEffect(pairing, hostState == HostState.Online, refreshTick) {
        val p = pairing ?: return@LaunchedEffect
        try {
            folders = withContext(Dispatchers.IO) {
                NesterApi(NesterApi.ApiConfig(p.host, p.port)).folders(p.token)
            }
            error = null
        } catch (e: Exception) {
            error = "Failed to load folders: ${e.message}"
            folders = emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HostStatusStrip(hostState, hostLabel, onRetryHost)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Folders", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = onUnpair) { Text("Unpair") }
        }
        when {
            folders == null -> Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.m),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    "Loading folders...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
            else -> {
                error?.let { InlineErrorRow(it, onRetry = { refreshTick++ }) }
                LazyColumn {
                    items(folders ?: emptyList(), key = { it.id }) { folder ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenFolder(folder) }
                                .padding(horizontal = Spacing.m, vertical = Spacing.s),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folder.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        folder.root,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    FolderStatsLine(folder.stats)
                                }
                                SyncStateChip(folder.stats?.state)
                                TextButton(onClick = { onBackupCamera(folder) }) { Text("Back up camera roll") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntriesScreen(
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
    var deleting by remember { mutableStateOf(false) }
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

    val dirs = folderState.entries.filter { it.kind == "dir" }.sortedBy { it.path }
    val files = folderState.entries.filter { it.kind != "dir" }.sortedBy { it.path }
    val isEmpty = dirs.isEmpty() && files.isEmpty()

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
        }
        PullToRefreshBox(
            isRefreshing = pulling,
            onRefresh = { pull() },
            modifier = Modifier.weight(1f),
        ) {
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
                    title = "This folder is empty on the host",
                    subtitle = "Files added to \"${folder.root}\" will appear here.",
                )
                else -> LazyColumn {
                    if (pulling) {
                        item {
                            Text(
                                "Syncing...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            )
                        }
                    }
                    error?.let { msg ->
                        item { InlineErrorRow(msg, onRetry = { pull() }) }
                    }
                    items(dirs, key = { "d:${it.path}" }) { dir ->
                        Text(
                            "/${dir.path}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    items(files, key = { "f:${it.path}" }) { file ->
                        EntryFileRow(
                            store = store,
                            folder = folder,
                            file = file,
                            downloadStates = downloadStates,
                            deleting = deleting,
                            onDeleteClick = { pendingDelete = file },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) pendingDelete = null },
            title = { Text("Delete \"${target.path.substringAfterLast('/')}\"?") },
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
private fun EntryFileRow(
    store: NesterStore,
    folder: FolderDto,
    file: EntryRecord,
    downloadStates: androidx.compose.runtime.MutableState<Map<String, DownloadState>>,
    deleting: Boolean,
    onDeleteClick: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = store.loadPairing()
    val scope = rememberCoroutineScope()
    val state = downloadStates.value[file.path] ?: DownloadState()
    val localFile = File(context.getExternalFilesDir(null), "nester/${folder.id}/${file.path}")
    val isOpenable = state.done && localFile.exists()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LocalFileThumb(
                localFile = if (localFile.exists()) localFile else null,
                modifier = Modifier.size(44.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.s),
            ) {
                Text(
                    file.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    "${formatSize(file.size)} · ${file.path.substringBeforeLast('/').ifEmpty { "/" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (!isOpenable) {
                OutlinedButton(
                    onClick = {
                        val p = pairing ?: return@OutlinedButton
                        downloadStates.value = downloadStates.value + (file.path to DownloadState(active = true))
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    NesterApi(NesterApi.ApiConfig(p.host, p.port)).download(
                                        token = p.token,
                                        folderId = folder.id,
                                        path = file.path,
                                        destFile = localFile,
                                        sizeBytes = file.size,
                                        mtimeS = file.mtimeS,
                                    ) { progress ->
                                        val total = progress.totalBytes
                                        val frac = if (total > 0) progress.downloadedBytes.toFloat() / total else 0f
                                        downloadStates.value = downloadStates.value + (file.path to DownloadState(active = true, progress = frac))
                                    }
                                }
                                downloadStates.value = downloadStates.value + (file.path to DownloadState(done = true))
                            } catch (e: Exception) {
                                downloadStates.value = downloadStates.value + (file.path to DownloadState(error = e.message))
                            }
                        }
                    },
                    enabled = !state.active,
                ) {
                    Text(
                        when {
                            state.active -> "${(state.progress * 100).toInt()}%"
                            state.done -> "Re-download"
                            else -> "Download"
                        },
                    )
                }
            }
            if (isOpenable) {
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(context, "app.nester.fileprovider", localFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }) { Text("Open") }
            }
            TextButton(onClick = onDeleteClick, enabled = !deleting) { Text("Delete") }
        }
        if (state.active) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
            )
        }
        state.error?.let {
            Text(
                "Download failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

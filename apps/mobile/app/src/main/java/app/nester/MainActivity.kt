package app.nester

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nester.api.EntryDto
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.pair.PairParseResult
import app.nester.pair.PairingParser
import app.nester.store.EntryRecord
import app.nester.store.FolderState
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import app.nester.ui.CameraRollScreen
import app.nester.ui.QrScanScreen
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NesterApp() {
    val context = LocalContext.current
    val store = remember { NesterStore(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Pair) }

    LaunchedEffect(Unit) {
        if (store.loadPairing() != null) screen = Screen.Folders
    }

    MaterialTheme {
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
                        onBack = { screen = Screen.Folders },
                    )
                    is Screen.CameraRoll -> CameraRollScreen(
                        store = store,
                        folder = s.folder,
                        onBack = { screen = Screen.Folders },
                    )
                }
            }
        }
    }
}

@Composable
fun PairScreen(
    store: NesterStore,
    onPaired: () -> Unit,
    onScanQr: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pair with host", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host (IP)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                error = null
                val portNum = port.toIntOrNull()
                if (host.isBlank() || portNum == null || portNum !in 1..65535 || token.length < 16) {
                    error = "Invalid host, port, or token (min 16 chars)"
                    return@Button
                }
                checking = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val api = NesterApi(NesterApi.ApiConfig(host.trim(), portNum))
                            api.health()
                        }
                        store.savePairing(StoredPairing(host.trim(), portNum, token.trim()))
                        onPaired()
                    } catch (e: Exception) {
                        error = "Health check failed: ${e.message}"
                    } finally {
                        checking = false
                    }
                }
            },
            enabled = !checking,
        ) {
            Text(if (checking) "Checking..." else "Save and verify")
        }
        OutlinedButton(onClick = onScanQr) { Text("Scan QR") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun FoldersScreen(
    store: NesterStore,
    onOpenFolder: (FolderDto) -> Unit,
    onBackupCamera: (FolderDto) -> Unit,
    onUnpair: () -> Unit,
) {
    var folders by remember { mutableStateOf<List<FolderDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pairing = store.loadPairing()

    LaunchedEffect(pairing) {
        if (pairing == null) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                val api = NesterApi(NesterApi.ApiConfig(pairing.host, pairing.port))
                api.folders(pairing.token)
            }.let { folders = it }
        } catch (e: Exception) {
            error = "Failed to load folders: ${e.message}"
            folders = emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onUnpair) { Text("Unpair") }
        }
        when {
            folders == null -> CircularProgressIndicator()
            else -> {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                LazyColumn {
                    items(folders ?: emptyList(), key = { it.id }) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenFolder(folder) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(folder.label, style = MaterialTheme.typography.bodyLarge)
                                Text(folder.root, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onBackupCamera(folder) }) { Text("Back up camera roll") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024f * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
fun EntriesScreen(store: NesterStore, folder: FolderDto, onBack: () -> Unit) {
    val context = LocalContext.current
    val pairing = store.loadPairing()
    var folderState by remember { mutableStateOf<FolderState>(store.loadFolder(folder.id)) }
    var pulling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<EntryRecord?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val downloadStates = remember { mutableStateOf<Map<String, DownloadState>>(emptyMap()) }
    var fileCounter by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun pull(sinceOverride: Long? = null) {
        val p = pairing ?: return
        pulling = true
        error = null
        scope.launch {
            try {
                val since = sinceOverride ?: folderState.lastSequence
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
            } catch (e: Exception) {
                error = "Pull failed: ${e.message}"
            } finally {
                pulling = false
            }
        }
    }

    LaunchedEffect(folder.id) { pull(0) }

    fun deleteFile(entry: EntryRecord) {
        val p = pairing ?: return
        deleting = true
        error = null
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text(folder.label, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { pull() }, enabled = !pulling) {
                Text(if (pulling) "Pulling..." else "Pull")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(dirs, key = { "d:${it.path}" }) { dir ->
                Text("/${dir.path}", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
            }
            items(files, key = { "f:${it.path}" }) { file ->
                val state = downloadStates.value[file.path] ?: DownloadState()
                val localFile = File(context.getExternalFilesDir(null), "nester/${folder.id}/${file.path}")
                val isOpenable = state.done && localFile.exists()
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                    Text("${formatSize(file.size)} - ${file.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!isOpenable) {
                            OutlinedButton(
                                onClick = {
                                    val p = pairing ?: return@OutlinedButton
                                    val id = fileCounter++
                                    downloadStates.value = downloadStates.value + (file.path to DownloadState(active = true))
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                NesterApi(NesterApi.ApiConfig(p.host, p.port)).download(
                                                    token = p.token,
                                                    folderId = folder.id,
                                                    path = file.path,
                                                    destFile = localFile,
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
                        TextButton(
                            onClick = { pendingDelete = file },
                            enabled = !deleting,
                        ) { Text("Delete") }
                    }
                    if (state.active) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                     state.error?.let { Text("Download failed: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                 }
                 HorizontalDivider()
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

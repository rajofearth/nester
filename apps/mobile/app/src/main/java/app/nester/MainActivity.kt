@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nester

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.api.sweepStalePartFiles
import app.nester.pair.PairParseResult
import app.nester.pair.PairingParser
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import app.nester.ui.CameraRollScreen
import app.nester.ui.GalleryScreen
import app.nester.ui.QrScanScreen
import app.nester.ui.common.FolderStatsLine
import app.nester.ui.common.InlineErrorRow
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

sealed interface Screen {
    data object Pair : Screen
    data object Folders : Screen
    data class Entries(val folder: FolderDto) : Screen
    data class CameraRoll(val folder: FolderDto) : Screen
    data object Qr : Screen
}

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
                    is Screen.Entries -> GalleryScreen(
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

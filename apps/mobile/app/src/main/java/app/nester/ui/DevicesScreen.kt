package app.nester.ui

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
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.store.NesterStore
import app.nester.ui.common.LeadingIconCircle
import app.nester.ui.common.NesterListItem
import app.nester.ui.common.StackedItem
import app.nester.ui.common.StackedListOuter
import app.nester.ui.common.formatCompact
import app.nester.ui.common.formatCount
import app.nester.ui.common.formatSize
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen(
    store: NesterStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
) {
    val pairing = remember { store.loadPairing() }
    var folders by remember { mutableStateOf<List<FolderDto>>(emptyList()) }
    var loadedAny by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showDetails by remember { mutableStateOf(false) }

    LaunchedEffect(pairing, hostState == HostState.Online, refreshTick) {
        val p = pairing ?: return@LaunchedEffect
        if (hostState != HostState.Online && loadedAny) return@LaunchedEffect
        try {
            val fetched = withContext(Dispatchers.IO) {
                NesterApi(NesterApi.ApiConfig(p.host, p.port)).folders(p.token)
            }
            folders = fetched
            loadedAny = true
        } catch (_: Exception) {
        }
    }

    val totalFiles = folders.sumOf { (it.stats?.files ?: 0).toLong() }
    val totalBytes = folders.sumOf { it.stats?.bytes ?: 0L }

    Column(modifier = Modifier.fillMaxSize()) {
        DevicesTopBar(state = hostState, onRetryHost = onRetryHost)
        HostStatusStrip(state = hostState, hostLabel = hostLabel, onRetry = onRetryHost)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
            ) {
                StackedListOuter {
                    StackedItem(onClick = { showDetails = true }) {
                        NesterListItem(
                            title = "Host - ${hostLabel ?: "unknown"}",
                            supporting = when (hostState) {
                                HostState.Online -> "Connected - In Sync (${formatCompact(totalFiles)} files)"
                                HostState.Checking -> "Checking connection..."
                                HostState.Offline -> "Disconnected - showing last sync"
                            },
                            leading = { LeadingIconCircle(Icons.Rounded.Computer) },
                            trailing = {
                                IconButton(onClick = { showDetails = true }) {
                                    Icon(
                                        Icons.Rounded.Wifi,
                                        contentDescription = "Connection details",
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

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            modifier = Modifier.width(312.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    "Host - ${hostLabel ?: "unknown"}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (hostState) {
                            HostState.Online -> "Connected - In Sync (${formatCount(totalFiles)} Files) - ${formatSize(totalBytes)}"
                            HostState.Checking -> "Checking connection..."
                            HostState.Offline -> "Disconnected. Connect to the same WiFi network as the host and retry."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DevicesTopBar(state: HostState, onRetryHost: () -> Unit) {
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

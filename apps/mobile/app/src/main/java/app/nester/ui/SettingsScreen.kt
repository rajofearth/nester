package app.nester.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nester.data.SettingsStore
import app.nester.store.NesterStore
import app.nester.ui.common.LeadingIconCircle
import app.nester.ui.common.NesterListItem
import app.nester.ui.common.StackedItem
import app.nester.ui.common.StackedListOuter
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import kotlinx.coroutines.launch

private fun hostStateLabel(state: HostState): String = when (state) {
    HostState.Online -> "Online"
    HostState.Checking -> "Checking"
    HostState.Offline -> "Offline"
}

@Composable
fun SettingsScreen(
    store: NesterStore,
    settings: SettingsStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onUnpair: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val context = LocalContext.current
    val pairing = remember { store.loadPairing() }
    val includeVideos by settings.includeVideos.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    var showUnpairConfirm by remember { mutableStateOf(false) }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopBar(state = hostState, onRetryHost = onRetryHost)
        HostStatusStrip(state = hostState, hostLabel = hostLabel, onRetry = onRetryHost)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.m),
        ) {
            Text(
                "Pairing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.s, top = Spacing.l, bottom = Spacing.s),
            )
            StackedListOuter {
                StackedItem(onClick = null) {
                    NesterListItem(
                        title = "Host",
                        supporting = "${pairing?.host ?: hostLabel ?: "unknown"}:${pairing?.port ?: 0} - ${hostStateLabel(hostState)}",
                        leading = { LeadingIconCircle(Icons.Rounded.Computer) },
                    )
                }
                StackedItem(onClick = onPairAgain) {
                    NesterListItem(
                        title = "Pair a different host",
                        leading = { LeadingIconCircle(Icons.Rounded.QrCodeScanner) },
                    )
                }
                StackedItem(onClick = { showUnpairConfirm = true }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        LeadingIconCircle(Icons.Rounded.LinkOff)
                        Text(
                            "Unpair from this host",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                        )
                    }
                }
            }
            Text(
                "Camera backup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.s, top = Spacing.l, bottom = Spacing.s),
            )
            StackedListOuter {
                StackedItem(onClick = null) {
                    NesterListItem(
                        title = "Include videos",
                        supporting = "Back up videos along with photos",
                        leading = { LeadingIconCircle(Icons.Rounded.Videocam) },
                        trailing = {
                            Switch(
                                checked = includeVideos,
                                onCheckedChange = { checked ->
                                    scope.launch { settings.setIncludeVideos(checked) }
                                },
                            )
                        },
                    )
                }
            }
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.s, top = Spacing.l, bottom = Spacing.s),
            )
            StackedListOuter {
                StackedItem(onClick = null) {
                    NesterListItem(
                        title = "App version",
                        supporting = versionName ?: "0.1.0",
                        leading = { LeadingIconCircle(Icons.Rounded.Info) },
                    )
                }
                StackedItem(onClick = null) {
                    NesterListItem(
                        title = "Sync model",
                        supporting = "Mirror - last-writer-wins on conflict",
                        leading = { LeadingIconCircle(Icons.Rounded.Sync) },
                    )
                }
            }
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            modifier = Modifier.width(312.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    "Unpair from this host",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    "This removes the pairing on this phone. You can pair again by scanning the host QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUnpairConfirm = false; onUnpair() }) {
                    Text("Unpair", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsTopBar(state: HostState, onRetryHost: () -> Unit) {
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

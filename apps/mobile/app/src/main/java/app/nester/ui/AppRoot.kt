package app.nester.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nester.api.FolderDto
import app.nester.api.sweepStalePartFiles
import app.nester.data.SettingsStore
import app.nester.store.NesterStore
import app.nester.ui.status.HeartbeatEffect
import app.nester.ui.status.HostStatusHolder
import app.nester.ui.theme.NesterTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Destination {
    data object Onboarding : Destination
    data object Home : Destination
    data object Devices : Destination
    data object Settings : Destination
    data class Folder(val folder: FolderDto) : Destination
    data class CameraBackup(val folder: FolderDto) : Destination
}

private fun Destination.orderIndex(): Int = when (this) {
    Destination.Home -> 0
    Destination.Devices -> 1
    Destination.Settings -> 2
    is Destination.Folder -> 3
    is Destination.CameraBackup -> 4
    Destination.Onboarding -> -1
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { NesterStore(context) }
    val settings = remember { SettingsStore(context) }
    val holder = remember { HostStatusHolder() }
    var retryTick by remember { mutableIntStateOf(0) }
    var paired by remember { mutableStateOf(store.loadPairing() != null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            context.getExternalFilesDir(null)?.let {
                sweepStalePartFiles(File(it, "nester"), System.currentTimeMillis())
            }
        }
    }

    HeartbeatEffect(
        active = paired,
        store = store,
        holder = holder,
        retryTick = retryTick,
    )

    NesterTheme {
        AnimatedContent(
            targetState = paired,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding",
        ) { isPaired ->
            if (!isPaired) {
                OnboardingScreen(
                    store = store,
                    settings = settings,
                    onConnected = { paired = true },
                )
            } else {
                PairedRoot(
                    store = store,
                    settings = settings,
                    holder = holder,
                    onRetryHost = { retryTick++ },
                    onUnpair = {
                        store.clearPairing()
                        paired = false
                    },
                    onPairAgain = { paired = false },
                )
            }
        }
    }
}

@Composable
private fun PairedRoot(
    store: NesterStore,
    settings: SettingsStore,
    holder: HostStatusHolder,
    onRetryHost: () -> Unit,
    onUnpair: () -> Unit,
    onPairAgain: () -> Unit,
) {
    var tab by remember { mutableStateOf<Destination>(Destination.Home) }
    var stack by remember { mutableStateOf<List<Destination>>(emptyList()) }
    val current = stack.lastOrNull() ?: tab
    val hostLabel = remember { store.loadPairing()?.host }

    BackHandler(enabled = stack.isNotEmpty()) {
        stack = stack.dropLast(1)
    }

    val onSelectTab: (Destination) -> Unit = { dest ->
        stack = emptyList()
        tab = dest
    }

    Scaffold(
        bottomBar = {
            if (current !is Destination.CameraBackup) {
                NesterNavigationBar(current = current, onSelect = onSelectTab)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (targetState.orderIndex() >= initialState.orderIndex()) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "screens",
            ) { dest ->
                when (dest) {
                    Destination.Home -> HomeScreen(
                        store = store,
                        settings = settings,
                        hostState = holder.state,
                        hostLabel = hostLabel,
                        onRetryHost = onRetryHost,
                        onOpenFolder = { stack = stack + Destination.Folder(it) },
                        onOpenCameraBackup = { stack = stack + Destination.CameraBackup(it) },
                        onUnpair = onUnpair,
                    )
                    Destination.Devices -> DevicesScreen(
                        store = store,
                        hostState = holder.state,
                        hostLabel = hostLabel,
                        onRetryHost = onRetryHost,
                    )
                    Destination.Settings -> SettingsScreen(
                        store = store,
                        settings = settings,
                        hostState = holder.state,
                        hostLabel = hostLabel,
                        onRetryHost = onRetryHost,
                        onUnpair = onUnpair,
                        onPairAgain = onPairAgain,
                    )
                    is Destination.Folder -> FolderScreen(
                        store = store,
                        folder = dest.folder,
                        settings = settings,
                        hostState = holder.state,
                        hostLabel = hostLabel,
                        onRetryHost = onRetryHost,
                        onBack = { stack = stack.dropLast(1) },
                        onOpenCameraBackup = { stack = stack + Destination.CameraBackup(it) },
                    )
                    is Destination.CameraBackup -> CameraBackupScreen(
                        store = store,
                        folder = dest.folder,
                        settings = settings,
                        hostState = holder.state,
                        hostLabel = hostLabel,
                        onRetryHost = onRetryHost,
                        onBack = { stack = stack.dropLast(1) },
                    )
                    Destination.Onboarding -> {}
                }
            }
        }
    }
}

@Composable
private fun NesterNavigationBar(current: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = current == Destination.Home,
            onClick = { onSelect(Destination.Home) },
            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
            label = { Text("Home", style = MaterialTheme.typography.labelMedium) },
        )
        NavigationBarItem(
            selected = current == Destination.Devices,
            onClick = { onSelect(Destination.Devices) },
            icon = { Icon(Icons.Rounded.DevicesOther, contentDescription = null) },
            label = { Text("Devices", style = MaterialTheme.typography.labelMedium) },
        )
        NavigationBarItem(
            selected = current == Destination.Settings,
            onClick = { onSelect(Destination.Settings) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            label = { Text("Settings", style = MaterialTheme.typography.labelMedium) },
        )
    }
}

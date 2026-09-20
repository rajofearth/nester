@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nester.api.ConflictException
import app.nester.api.FolderDto
import app.nester.api.NesterApi
import app.nester.backup.BackupFailure
import app.nester.backup.BackupPhase
import app.nester.backup.BackupPlanItem
import app.nester.backup.BackupRun
import app.nester.backup.BackupRunState
import app.nester.backup.CameraRollSource
import app.nester.backup.ItemStatus
import app.nester.backup.MediaItem
import app.nester.backup.TransferRateTracker
import app.nester.backup.backupEndStateHeadline
import app.nester.backup.etaSeconds
import app.nester.backup.groupFailures
import app.nester.backup.planBackup
import app.nester.backup.processedCount
import app.nester.data.SettingsStore
import app.nester.store.NesterStore
import app.nester.ui.common.ChipState
import app.nester.ui.common.EmptyPane
import app.nester.ui.common.StackedItem
import app.nester.ui.common.StatusChip
import app.nester.ui.common.formatDuration
import app.nester.ui.common.formatEta
import app.nester.ui.common.formatSize
import app.nester.ui.common.formatSpeed
import app.nester.ui.common.rememberMediaImageLoader
import app.nester.ui.status.HostState
import app.nester.ui.status.HostStatusStrip
import app.nester.ui.theme.Spacing
import coil.ImageLoader
import coil.compose.AsyncImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mediaReadPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun CameraBackupScreen(
    store: NesterStore,
    folder: FolderDto,
    settings: SettingsStore,
    hostState: HostState,
    hostLabel: String?,
    onRetryHost: () -> Unit,
    onBack: () -> Unit,
) {
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
    var failures by remember { mutableStateOf<List<BackupFailure>>(emptyList()) }
    var expandedErrorId by remember { mutableStateOf<String?>(null) }
    var lastPlan by remember { mutableStateOf<List<BackupPlanItem>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folder.id) {
        settings.setCameraFolderId(folder.id)
    }

    val includeVideos by settings.includeVideos.collectAsState(initial = true)

    fun load() {
        val loaded = CameraRollSource.query(context, includeVideos)
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

    LaunchedEffect(hasPermission, includeVideos) {
        if (hasPermission) load()
    }

    val imageLoader = rememberMediaImageLoader()

    fun startRun(planItems: List<BackupPlanItem>) {
        val p = pairing ?: return
        if (planItems.isEmpty()) return
        val api = NesterApi(NesterApi.ApiConfig(p.host, p.port))
        val tracker = TransferRateTracker()
        val totalBytes = planItems.sumOf { it.item.size }
        val ids = planItems.map { it.item.mediaStoreId }
        lastPlan = planItems
        failures = failures.filterNot { it.itemId in ids.toSet() }
        BackupRunState.statuses = planItems.fold(BackupRunState.statuses) { acc, pi -> acc + (pi.item.mediaStoreId to ItemStatus.QUEUED) }
        BackupRunState.run = BackupRun(
            phase = BackupPhase.RUNNING,
            planIds = ids,
            sentBytes = 0,
            totalBytes = totalBytes,
            bytesPerSecond = 0,
            startedMs = System.currentTimeMillis(),
        )
        BackupRunState.paused = false
        var sentBefore = 0L
        scope.launch {
            for (pi in planItems) {
                while (BackupRunState.paused) {
                    BackupRunState.run = BackupRunState.run?.copy(phase = BackupPhase.PAUSED)
                    delay(200)
                }
                BackupRunState.run = BackupRunState.run?.copy(phase = BackupPhase.RUNNING)
                val id = pi.item.mediaStoreId
                BackupRunState.statuses = BackupRunState.statuses + (id to ItemStatus.UPLOADING)
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
                                tracker.record(System.currentTimeMillis(), sentBefore + prog.sentBytes)
                                BackupRunState.run = BackupRunState.run?.copy(
                                    sentBytes = sentBefore + prog.sentBytes,
                                    bytesPerSecond = tracker.bytesPerSecond(),
                                )
                            }
                        }
                    }
                    BackupRunState.statuses = BackupRunState.statuses + (id to ItemStatus.DONE)
                    sentBefore += pi.item.size
                    BackupRunState.run = BackupRunState.run?.copy(sentBytes = sentBefore)
                    val now = System.currentTimeMillis()
                    uploaded = uploaded + (id to now)
                    selected = selected - id
                    val fs = withContext(Dispatchers.IO) { store.loadFolder(folder.id) }
                    withContext(Dispatchers.IO) {
                        store.saveFolder(folder.id, fs.copy(uploadedMedia = fs.uploadedMedia + (id to now)))
                    }
                } catch (e: ConflictException) {
                    BackupRunState.statuses = BackupRunState.statuses + (id to ItemStatus.CONFLICT)
                } catch (e: Exception) {
                    BackupRunState.statuses = BackupRunState.statuses + (id to ItemStatus.FAILED)
                    failures = failures + BackupFailure(id, e.message ?: "unknown error")
                }
            }
            BackupRunState.run = BackupRunState.run?.copy(phase = BackupPhase.DONE, endedMs = System.currentTimeMillis(), sentBytes = sentBefore)
        }
    }

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
            Text(
                "Camera backup",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Spacing.s),
            )
        }
        HostStatusStrip(hostState, hostLabel, onRetryHost)

        when {
            !hasPermission -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.m),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Camera backup needs access to your photos and videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { imageLauncher.launch(mediaReadPermission()) },
                    modifier = Modifier.padding(top = Spacing.s),
                ) { Text("Grant access") }
            }
            items == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            items != null && items!!.isEmpty() -> EmptyPane(
                icon = Icons.Rounded.CloudDone,
                title = "No photos found",
                subtitle = "Photos and videos on this phone show up here for backup.",
            )
            else -> {
                val list = items ?: emptyList()
                val pendingItems = list.filter { it.mediaStoreId !in uploaded }
                val runActive = BackupRunState.run != null && BackupRunState.run?.phase != BackupPhase.DONE
                val doneInPlan = BackupRunState.run?.planIds?.count { BackupRunState.statuses[it] == ItemStatus.DONE } ?: 0
                val failedInPlan = BackupRunState.run?.planIds?.count { BackupRunState.statuses[it] == ItemStatus.FAILED } ?: 0
                val conflictInPlan = BackupRunState.run?.planIds?.count { BackupRunState.statuses[it] == ItemStatus.CONFLICT } ?: 0

                BackupStateCard(
                    run = BackupRunState.run,
                    doneInPlan = doneInPlan,
                    failedInPlan = failedInPlan,
                    conflictInPlan = conflictInPlan,
                    pendingCount = pendingItems.size,
                    pendingBytes = pendingItems.sumOf { it.size },
                )
                if (!runActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    ) {
                        TextButton(onClick = { selected = pendingItems.map { it.mediaStoreId }.toSet() }) {
                            Text("Select all")
                        }
                        TextButton(onClick = { selected = emptySet() }) {
                            Text("Deselect all")
                        }
                    }
                }
                if (BackupRunState.run?.phase == BackupPhase.DONE) {
                    BackupRunState.run?.let { r ->
                        BackupSummaryCard(
                            run = r,
                            statuses = BackupRunState.statuses,
                            itemsById = list.associateBy { it.mediaStoreId },
                            failures = failures,
                            onRetryFailed = {
                                val retryPlan = lastPlan
                                    ?.filter { BackupRunState.statuses[it.item.mediaStoreId] == ItemStatus.FAILED }
                                    ?: emptyList()
                                startRun(retryPlan)
                            },
                            onDone = {
                                BackupRunState.run = null
                                BackupRunState.statuses = emptyMap()
                                failures = emptyList()
                                expandedErrorId = null
                            },
                        )
                    }
                }
                if (runActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    ) {
                        Button(
                            onClick = { BackupRunState.paused = !BackupRunState.paused },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (BackupRunState.paused) "Resume" else "Pause") }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = Spacing.xs),
                    contentPadding = PaddingValues(horizontal = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(list, key = { it.mediaStoreId }) { item ->
                        MediaRow(
                            item = item,
                            imageLoader = imageLoader,
                            isUploaded = item.mediaStoreId in uploaded,
                            isSelected = item.mediaStoreId in selected,
                            status = BackupRunState.statuses[item.mediaStoreId],
                            failureMessage = failures.lastOrNull { it.itemId == item.mediaStoreId }?.message,
                            expanded = expandedErrorId == item.mediaStoreId,
                            onExpandError = {
                                expandedErrorId = if (expandedErrorId == item.mediaStoreId) null else item.mediaStoreId
                            },
                            onChecked = { checked ->
                                selected = if (checked) selected + item.mediaStoreId else selected - item.mediaStoreId
                            },
                            runActive = runActive,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                ) {
                    Button(
                        onClick = { confirm = true },
                        enabled = !runActive && selected.isNotEmpty() && hostState != HostState.Offline,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            if (runActive) (if (BackupRunState.paused) "Paused" else "Backing up...") else "Back up ${selected.size} items",
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                }
            }
        }
    }

    if (confirm) {
        val plan = planBackup(items ?: emptyList(), uploaded).filter { it.item.mediaStoreId in selected }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Back up ${plan.size} items?") },
            text = { Text("Selected photos and videos will be uploaded to \"${folder.label}\" on the host.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    startRun(plan)
                }) { Text("Back up") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackupStateCard(
    run: BackupRun?,
    doneInPlan: Int,
    failedInPlan: Int,
    conflictInPlan: Int,
    pendingCount: Int,
    pendingBytes: Long,
) {
    val processedInPlan = processedCount(doneInPlan, conflictInPlan, failedInPlan)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Column(modifier = Modifier.padding(Spacing.m)) {
            when {
                run == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pendingCount > 0) {
                            Text(
                                "Not backed up: $pendingCount items (${formatSize(pendingBytes)})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                "All backed up",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = Spacing.s),
                            )
                        }
                    }
                }
                run.phase == BackupPhase.DONE -> {
                    if (failedInPlan > 0 || conflictInPlan > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                backupEndStateHeadline(doneInPlan, run.planIds.size, conflictInPlan, failedInPlan),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = Spacing.s),
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                backupEndStateHeadline(doneInPlan, run.planIds.size, conflictInPlan, failedInPlan),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = Spacing.s),
                            )
                        }
                    }
                }
                run.phase == BackupPhase.PAUSED -> {
                    Text(
                        "Paused - ${processedInPlan + 1} of ${run.planIds.size} items (${formatSize(run.totalBytes - run.sentBytes)} left)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                run.phase == BackupPhase.RUNNING -> {
                    Text(
                        "Backing up - ${(processedInPlan + 1).coerceAtMost(run.planIds.size)} of ${run.planIds.size} items (${formatSize(run.totalBytes - run.sentBytes)} left)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            if (run != null) {
                val frac = if (run.totalBytes > 0) run.sentBytes.toFloat() / run.totalBytes else 0f
                LinearProgressIndicator(
                    progress = { frac.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.s),
                )
                if (run.phase == BackupPhase.RUNNING) {
                    val eta = etaSeconds(run.totalBytes - run.sentBytes, run.bytesPerSecond)
                    Text(
                        "${formatSpeed(run.bytesPerSecond)} · ${formatEta(eta ?: -1)} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupSummaryCard(
    run: BackupRun,
    statuses: Map<String, ItemStatus>,
    itemsById: Map<String, MediaItem>,
    failures: List<BackupFailure>,
    onRetryFailed: () -> Unit,
    onDone: () -> Unit,
) {
    val doneIds = run.planIds.filter { statuses[it] == ItemStatus.DONE }
    val conflictCount = run.planIds.count { statuses[it] == ItemStatus.CONFLICT }
    val failedCount = run.planIds.count { statuses[it] == ItemStatus.FAILED }
    val doneBytes = doneIds.sumOf { itemsById[it]?.size ?: 0 }
    val tookMs = (run.endedMs ?: run.startedMs) - run.startedMs
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Column(modifier = Modifier.padding(Spacing.m)) {
            Text(
                "Backed up ${doneIds.size} of ${run.planIds.size} · ${formatSize(doneBytes)} · took ${formatDuration(tookMs / 1000)}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (conflictCount > 0) {
                Text(
                    "$conflictCount skipped: host has newer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            if (failedCount > 0) {
                Text(
                    "$failedCount failed",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                val runIds = run.planIds.toSet()
                val last = failures.lastOrNull { it.itemId in runIds }
                last?.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                val groups = groupFailures(failures.filter { it.itemId in runIds }.map { it.message })
                Text(
                    "errors: ${groups.joinToString(", ") { "${it.count}× ${it.prefix}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Row(
                modifier = Modifier.padding(top = Spacing.s),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                if (failedCount > 0) {
                    Button(onClick = onRetryFailed) { Text("Retry failed") }
                }
                TextButton(onClick = onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun MediaRow(
    item: MediaItem,
    imageLoader: ImageLoader,
    isUploaded: Boolean,
    isSelected: Boolean,
    status: ItemStatus?,
    failureMessage: String?,
    expanded: Boolean,
    runActive: Boolean,
    onExpandError: () -> Unit,
    onChecked: (Boolean) -> Unit,
) {
    StackedItem(onClick = if (status == ItemStatus.FAILED) onExpandError else null) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    AsyncImage(
                        model = item.contentUri,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.s),
                ) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatSize(item.size)} - ${item.relativeDir.ifEmpty { "/" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val chip = rowChip(isUploaded, status)
                StatusChip(label = chip.label, state = chip.state, leading = chip.leading)
                if (!runActive && status == null && !isUploaded) {
                    Checkbox(checked = isSelected, onCheckedChange = onChecked)
                }
            }
            if (status == ItemStatus.FAILED && expanded && failureMessage != null) {
                Text(
                    failureMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.s),
                )
            }
        }
    }
}

private data class RowChip(val label: String, val state: ChipState, val leading: ImageVector?)

private fun rowChip(
    isUploaded: Boolean,
    status: ItemStatus?,
): RowChip = when {
    isUploaded || status == ItemStatus.DONE ->
        RowChip("Backed up", ChipState.Selected, Icons.Rounded.Check)
    status == ItemStatus.UPLOADING ->
        RowChip("Syncing", ChipState.Progress, Icons.Rounded.Sync)
    status == ItemStatus.QUEUED ->
        RowChip("Queued", ChipState.Progress, null)
    status == ItemStatus.CONFLICT ->
        RowChip("Host has newer", ChipState.Progress, Icons.Rounded.SyncProblem)
    status == ItemStatus.FAILED ->
        RowChip("Failed", ChipState.Error, Icons.Rounded.SyncProblem)
    else ->
        RowChip("Pending", ChipState.Neutral, Icons.Rounded.CloudUpload)
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

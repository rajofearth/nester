package app.nester.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object BackupRunState {
    var run by mutableStateOf<BackupRun?>(null)
    var statuses by mutableStateOf<Map<String, ItemStatus>>(emptyMap())
    var paused by mutableStateOf(false)
}

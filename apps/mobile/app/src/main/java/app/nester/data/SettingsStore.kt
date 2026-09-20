package app.nester.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nester_settings")

class SettingsStore(private val context: Context) {

    private val includeVideosKey = booleanPreferencesKey("camera_backup_include_videos")
    private val cameraFolderKey = stringPreferencesKey("camera_backup_folder_id")

    val includeVideos: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[includeVideosKey] ?: true
    }

    val cameraFolderId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[cameraFolderKey]
    }

    suspend fun setIncludeVideos(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[includeVideosKey] = value }
    }

    suspend fun setCameraFolderId(value: String?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(cameraFolderKey) else prefs[cameraFolderKey] = value
        }
    }
}

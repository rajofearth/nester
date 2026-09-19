package app.nester.store

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class EntryRecord(
    val path: String,
    val kind: String,
    val size: Long,
    val mtimeS: Long,
    val deleted: Boolean,
    val hash: String?,
    val sequence: Long,
)

@Serializable
data class FolderState(
    val lastSequence: Long = 0,
    val entries: List<EntryRecord> = emptyList(),
    val uploadedMedia: Map<String, Long> = emptyMap(),
)

@Serializable
data class StoredPairing(
    val host: String,
    val port: Int,
    val token: String,
)

class NesterStore(context: Context) {
    private val dir = File(context.filesDir, "store")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    private fun pairingFile(): File = File(dir, "pairing.json")

    private fun folderFile(folderId: String): File {
        val safe = folderId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "folder_$safe.json")
    }

    @Synchronized
    fun loadPairing(): StoredPairing? {
        val f = pairingFile()
        if (!f.exists()) return null
        return try {
            json.decodeFromString<StoredPairing>(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun savePairing(pairing: StoredPairing) {
        pairingFile().writeText(json.encodeToString(pairing))
    }

    @Synchronized
    fun clearPairing() {
        pairingFile().delete()
    }

    @Synchronized
    fun loadFolder(folderId: String): FolderState {
        val f = folderFile(folderId)
        if (!f.exists()) return FolderState()
        return try {
            json.decodeFromString<FolderState>(f.readText())
        } catch (_: Exception) {
            FolderState()
        }
    }

    @Synchronized
    fun saveFolder(folderId: String, state: FolderState) {
        folderFile(folderId).writeText(json.encodeToString(state))
    }
}

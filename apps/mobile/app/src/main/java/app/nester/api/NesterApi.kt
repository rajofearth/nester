package app.nester.api

import android.net.Uri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Serializable
data class FolderStatsDto(
    val files: Int = 0,
    val bytes: Long = 0,
    @SerialName("last_change_at") val lastChangeAt: Long = 0,
    @SerialName("last_change_path") val lastChangePath: String? = null,
    val state: String? = null,
)

@Serializable
data class FolderDto(
    val id: String,
    val label: String,
    val root: String,
    val stats: FolderStatsDto? = null,
)

@Serializable
data class FoldersResponseDto(
    val folders: List<FolderDto>,
)

@Serializable
data class EntryDto(
    val path: String,
    val kind: String,
    val size: Long = 0,
    val mtimeS: Long = 0,
    val mtimeNs: Long = 0,
    val deleted: Boolean = false,
    val hash: String? = null,
    val sequence: Long = 0,
)

@Serializable
data class HealthDto(
    val ok: Boolean,
)

open class ApiException(val code: Int, message: String) : IOException(message)

class ConflictException(message: String) : ApiException(409, message)

class IndexResetException(message: String) : ApiException(409, message)

data class UploadProgress(val sentBytes: Long, val totalBytes: Long)

private const val STORAGE_MARGIN_BYTES: Long = 50L * 1024 * 1024

class NesterApi(private val config: ApiConfig, httpClient: OkHttpClient? = null) {

    data class ApiConfig(val host: String, val port: Int) {
        val baseUrl: String get() = "http://$host:$port"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun request(path: String, token: String): Request.Builder =
        Request.Builder()
            .url(config.baseUrl + path)
            .header("Authorization", "Bearer $token")

    private fun encodePath(path: String): String = encodePathSegments(path, Uri::encode)

    fun health(): HealthDto {
        val req = request("/api/health", "").build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("empty body")
            if (!resp.isSuccessful) throw IOException("health failed: ${resp.code}")
            return json.decodeFromString<HealthDto>(body)
        }
    }

    fun folders(token: String): List<FolderDto> {
        val req = request("/api/folders", token).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("empty body")
            if (!resp.isSuccessful) throw IOException("folders failed: ${resp.code}")
            return json.decodeFromString<FoldersResponseDto>(body).folders
        }
    }

    fun heartbeat(token: String) {
        val req = request("/api/heartbeat", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("heartbeat failed: ${resp.code}")
        }
    }

    fun entries(token: String, folderId: String, since: Long): List<EntryDto> {
        val req = request("/api/folders/$folderId/entries?since=$since", token).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 409) {
                throw IndexResetException(
                    resp.body?.string()?.trim().takeUnless { it.isNullOrEmpty() } ?: "index-reset",
                )
            }
            val body = resp.body?.string() ?: throw IOException("empty body")
            if (!resp.isSuccessful) throw IOException("entries failed: ${resp.code}")
            return json.decodeFromString<List<EntryDto>>(body)
        }
    }

    data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

    fun download(
        token: String,
        folderId: String,
        path: String,
        destFile: File,
        sizeBytes: Long = -1L,
        mtimeS: Long? = null,
        mtimeNs: Long? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ) {
        destFile.parentFile?.mkdirs()
        val dir = destFile.parentFile
        if (sizeBytes >= 0 && dir != null && dir.usableSpace < sizeBytes + STORAGE_MARGIN_BYTES) {
            throw IOException("not enough storage on phone")
        }
        val req = request(encodePath("/api/folders/$folderId/files/$path"), token).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("download failed: ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val tmp = File(destFile.absolutePath + ".part")
            FileOutputStream(tmp).use { out ->
                val src = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress(DownloadProgress(read, total))
                }
                out.flush()
                out.channel.force(true)
            }
            if (total >= 0 && tmp.length() != total) {
                tmp.delete()
                throw IOException("incomplete download: ${tmp.length()}/$total")
            }
            if (destFile.exists()) destFile.delete()
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }
            if (mtimeS != null) {
                destFile.setLastModified(mtimeS * 1000 + (mtimeNs ?: 0) / 1_000_000)
            }
        }
    }

    fun upload(
        token: String,
        folderId: String,
        path: String,
        file: File,
        mtimeS: Long? = null,
        mtimeNs: Long? = null,
        onProgress: (UploadProgress) -> Unit = {},
    ) {
        uploadStream(token, folderId, path, { file.inputStream() }, file.length(), mtimeS, mtimeNs, onProgress)
    }

    fun uploadStream(
        token: String,
        folderId: String,
        path: String,
        openStream: () -> InputStream,
        size: Long,
        mtimeS: Long? = null,
        mtimeNs: Long? = null,
        onProgress: (UploadProgress) -> Unit = {},
    ) {
        val builder = request(encodePath("/api/folders/$folderId/files/$path"), token)
            .post(StreamingRequestBody(openStream, size, onProgress))
        if (mtimeS != null) builder.header("X-Nester-Mtime-S", mtimeS.toString())
        if (mtimeNs != null) builder.header("X-Nester-Mtime-Ns", mtimeNs.toString())
        uploadClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 409) {
                throw ConflictException(resp.body?.string()?.trim().takeUnless { it.isNullOrEmpty() } ?: "stale version: pull first")
            }
            if (!resp.isSuccessful) throw ApiException(resp.code, "upload failed: ${resp.code}")
        }
    }

    fun deleteFile(token: String, folderId: String, path: String) {
        val req = request(encodePath("/api/folders/$folderId/files/$path"), token).delete().build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return
            if (!resp.isSuccessful) throw ApiException(resp.code, "delete failed: ${resp.code}")
        }
    }
}

private class StreamingRequestBody(
    private val openStream: () -> InputStream,
    private val size: Long,
    private val onProgress: (UploadProgress) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = size

    override fun writeTo(sink: BufferedSink) {
        openStream().use { src ->
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            while (true) {
                val n = src.read(buf)
                if (n == -1) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(UploadProgress(sent, size))
            }
            sink.flush()
        }
    }
}

internal fun encodePathSegments(path: String, encode: (String) -> String): String =
    path.split('/').joinToString("/") { segment ->
        if (segment.isEmpty()) "" else encode(segment)
    }

package dev.aarso.mail.app.ai

import dev.aarso.mail.model.SecretRef
import dev.aarso.mail.sync.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * A model you can fetch to this phone. Nothing here is bundled: the app ships only the catalogue,
 * and the weights are downloaded on demand into private storage (and deleted from there).
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val note: String,
    val url: String,
    val fileName: String,
    /** Approximate download size, for the "are you on Wi-Fi?" moment. */
    val approxBytes: Long,
    /** The host requires a signed-in token and an accepted licence (Gemma on Hugging Face). */
    val needsToken: Boolean = false,
    val licenseUrl: String? = null,
)

object ModelCatalog {
    val all = listOf(
        CatalogModel(
            id = "gemma3-1b-int4", name = "Gemma 3 1B (int4)",
            note = "Small and quick; summaries, sorting, rewriting. Runs on most phones from the last four years.",
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            fileName = "gemma3-1b-it-int4.task", approxBytes = 555_000_000L,
            needsToken = true, licenseUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
        ),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val done: Long, val total: Long) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** Resumable downloads of catalogue (or custom-address) models into `files/models`. */
class ModelDownloads(private val dir: File, private val http: OkHttpClient, private val secrets: SecretStore) {
    private val _state = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val state: StateFlow<Map<String, DownloadState>> = _state.asStateFlow()

    private val tokenRef = SecretRef("models.hf.token")
    suspend fun token(): String? = secrets.get(tokenRef)
    suspend fun setToken(t: String?) { if (t.isNullOrBlank()) secrets.delete(tokenRef) else secrets.put(tokenRef, t.trim()) }

    fun file(m: CatalogModel) = File(dir, m.fileName)
    fun installed(m: CatalogModel) = file(m).let { it.isFile && it.length() > 0 && !File(dir, m.fileName + ".part").exists() }

    private fun set(id: String, s: DownloadState) = _state.update { it + (id to s) }

    /** Returns the finished file, or null (the reason is in [state]). Cancelling the caller keeps the partial for resume. */
    suspend fun download(m: CatalogModel): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, m.fileName + ".part")
        val out = file(m)
        try {
            val have = part.takeIf { it.isFile }?.length() ?: 0L
            val req = Request.Builder().url(m.url).apply {
                if (have > 0) header("Range", "bytes=$have-")
                token()?.let { header("Authorization", "Bearer $it") }
            }.build()
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 401 || r.code == 403 -> { set(m.id, DownloadState.Failed(if (m.needsToken) "Needs a Hugging Face token whose account has accepted the model's licence" else "The server refused the download (${r.code})")); return@withContext null }
                    r.code == 416 -> { /* already complete */ part.renameTo(out); set(m.id, DownloadState.Idle); return@withContext out }
                    !r.isSuccessful -> { set(m.id, DownloadState.Failed("Download failed (${r.code})")); return@withContext null }
                }
                val resumed = r.code == 206
                val start = if (resumed) have else 0L
                val total = start + (r.body?.contentLength()?.takeIf { it > 0 } ?: (m.approxBytes - start))
                RandomAccessFile(part, "rw").use { f ->
                    if (!resumed) f.setLength(0)
                    f.seek(start)
                    val buf = ByteArray(256 * 1024)
                    var done = start
                    val src = r.body!!.source()
                    while (coroutineContext.isActive) {
                        val n = src.read(buf)
                        if (n < 0) break
                        f.write(buf, 0, n); done += n
                        set(m.id, DownloadState.Running(done, total))
                    }
                    if (!coroutineContext.isActive) throw CancellationException()
                }
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) { set(m.id, DownloadState.Failed("Couldn't save the model")); return@withContext null }
            set(m.id, DownloadState.Idle)
            out
        } catch (e: CancellationException) {
            set(m.id, DownloadState.Idle); throw e
        } catch (e: java.io.IOException) {
            set(m.id, DownloadState.Failed(if (e.message?.contains("ENOSPC") == true) "Not enough space" else "Connection lost — tap Download to resume")); null
        }
    }

    fun delete(m: CatalogModel) { file(m).delete(); File(dir, m.fileName + ".part").delete(); set(m.id, DownloadState.Idle) }
}

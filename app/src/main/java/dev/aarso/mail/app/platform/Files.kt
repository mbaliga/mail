package dev.aarso.mail.app.platform

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.aarso.mail.model.DraftAttachment
import dev.aarso.mail.sync.AttachmentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Attachments in and out: reading picked files for drafts, and handing downloaded parts to other apps. */
class Files(private val context: Context) : AttachmentLoader {

    override suspend fun load(attachment: DraftAttachment): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot read ${attachment.fileName}")
    }

    /** Describe a picked document (name, size, type) without reading it. */
    fun describe(uri: Uri): DraftAttachment {
        var name = uri.lastPathSegment ?: "attachment"
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                size = c.getLong(1)
            }
        }
        val mime = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        // Keep access to the document across process death until the draft is sent.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return DraftAttachment(uri.toString(), name, mime, size)
    }

    private fun cacheFile(dir: String, name: String): File {
        val folder = File(context.cacheDir, dir).apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120).ifBlank { "file" }
        return File(folder, safe)
    }

    private val outgoingDir by lazy { File(context.filesDir, "outgoing").apply { mkdirs() } }

    /**
     * A durable copy for an outgoing attachment. Cached files can be evicted by the system before a
     * message scheduled for next week goes out; these live in private storage until nothing refers to
     * them ([prune]).
     */
    suspend fun stage(bytes: ByteArray, name: String, mime: String): DraftAttachment = withContext(Dispatchers.IO) {
        val f = File(outgoingDir, "${System.nanoTime()}-" + name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(100).ifBlank { "file" })
        f.writeBytes(bytes)
        DraftAttachment(FileProvider.getUriForFile(context, "${context.packageName}.files", f).toString(), name, mime, bytes.size.toLong())
    }

    /** Copy something another app shared (a screenshot, a clipboard image) into [stage]'s storage. */
    suspend fun stageFrom(uri: Uri, name: String, mime: String): DraftAttachment? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return@withContext null
        stage(bytes, name, mime)
    }

    /** Remove staged files older than [olderThanMs] that no draft or outbox row refers to. */
    fun prune(inUse: Set<String>, olderThanMs: Long = 24 * 3_600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val keep = inUse.mapNotNull { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }.toSet()
        outgoingDir.listFiles()?.forEach { if (it.lastModified() < cutoff && it.name !in keep) it.delete() }
    }

    /** A content:// URI another app can open, for bytes we hold. */
    suspend fun shareable(bytes: ByteArray, name: String, dir: String = "attachments"): Uri = withContext(Dispatchers.IO) {
        val f = cacheFile(dir, name)
        f.writeBytes(bytes)
        FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun openIntent(uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(uri: Uri, mime: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        null,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Save into Downloads (MediaStore), visible in the Files app. Returns the new item's URI. */
    suspend fun saveToDownloads(bytes: ByteArray, name: String, mime: String): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/You've got Mail")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    suspend fun readUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }
}

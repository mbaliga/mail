package dev.aarso.mail.app.platform

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Things worth offering as an attachment right now: the screenshot you just took, and whatever you
 * just copied — so writing "see below" is one tap, not a trip through a file picker.
 *
 * Both are conservative about privacy. The clipboard is only *peeked at*: its type and age come from
 * the clip's description, which does not read the contents (so Android shows no "pasted from
 * clipboard" notice), and the contents are read only when the person accepts. Screenshots need photo
 * access, which the person grants once, on purpose.
 */
class AttachSuggestions(private val context: Context) {

    data class Screenshot(val uri: Uri, val name: String, val takenAt: Long, val mime: String)

    enum class ClipKind { IMAGE, TEXT, FILE }
    data class Clip(val kind: ClipKind, val mime: String, val copiedAt: Long, val label: String) {
        /** Identifies this clip so a dismissed suggestion does not come back. */
        val key: String get() = "clip:$copiedAt"
    }

    fun hasPhotoAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** The newest screenshot taken within [withinMs], or null. Needs [hasPhotoAccess]. */
    suspend fun latestScreenshot(withinMs: Long = 3 * 60_000L, now: Long = System.currentTimeMillis()): Screenshot? = withContext(Dispatchers.IO) {
        if (!hasPhotoAccess()) return@withContext null
        val since = (now - withinMs) / 1000
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString(), "%Screenshot%"))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE),
                args, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                Screenshot(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1) ?: "screenshot.png", c.getLong(2) * 1000, c.getString(3) ?: "image/png",
                )
            }
        }.getOrNull()
    }

    /** What was copied in the last [withinMs], judged by its description alone (nothing is read). */
    fun recentClip(withinMs: Long = 2 * 60_000L, now: Long = System.currentTimeMillis()): Clip? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val d = runCatching { cm.primaryClipDescription }.getOrNull() ?: return null
        val at = d.timestamp
        if (at <= 0 || now - at > withinMs) return null
        val mime = (0 until d.mimeTypeCount).map { d.getMimeType(it) }
        val kind = when {
            mime.any { it.startsWith("image/") } -> ClipKind.IMAGE
            mime.any { it == "text/plain" || it == "text/html" } -> ClipKind.TEXT
            mime.any { it.startsWith("application/") || it.startsWith("video/") || it.startsWith("audio/") } -> ClipKind.FILE
            else -> return null
        }
        return Clip(kind, mime.first(), at, when (kind) { ClipKind.IMAGE -> "an image"; ClipKind.TEXT -> "some text"; ClipKind.FILE -> "a file" })
    }

    /** Read the clip now (the person accepted). Text comes back as a string, files and images as a URI. */
    fun readClip(): Pair<String?, Uri?> {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null to null
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null to null
        return item.uri?.let { null to it } ?: (runCatching { item.coerceToText(context).toString() }.getOrNull() to null)
    }
}

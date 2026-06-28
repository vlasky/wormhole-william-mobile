package io.sanford.wormhole_william.util

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.sanford.wormhole_william.R
import java.io.File
import java.io.FileInputStream

private const val CHANNEL_ID = "wormhole_downloads"
private const val NOTIFICATION_ID = 1001

// Max filename length on Android storage (vfat/ext4 both cap at 255 bytes).
private const val MAX_FILENAME_BYTES = 255

/**
 * Registers a file with Android's Download Manager so it appears in the Downloads app.
 * Also copies the file to the public Downloads directory on Android 10+.
 */
fun Context.notifyDownloadManager(
    name: String,
    path: String,
    mimeType: String,
    size: Long
): Result<Uri> {
    return try {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+, use MediaStore to copy to Downloads
            copyToDownloadsViaMediaStore(name, path, mimeType, size)
        } else {
            // On older Android, copy to public Downloads and register with DownloadManager
            copyToDownloadsLegacy(name, path, mimeType, size)
        }

        // Show notification
        showDownloadCompleteNotification(name, uri, mimeType)

        Result.success(uri)
    } catch (e: Exception) {
        e.printStackTrace()
        Result.failure(e)
    }
}

/**
 * Resolves the actual display name of a saved download. On Android 10+ the
 * MediaStore automatically appends a numeric suffix (e.g. "report (1).pdf")
 * when a file with the same name already exists in Downloads, so the saved
 * name may differ from the requested one.
 */
fun Context.queryDownloadDisplayName(uri: Uri): String? {
    // file:// URIs (legacy path) carry the name directly; ContentResolver does
    // not answer OpenableColumns for them.
    if (uri.scheme == "file") return uri.lastPathSegment
    return try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
    } catch (e: Exception) {
        null
    }
}

/**
 * Atomically creates and returns a new (empty) File in [dir], appending
 * " (1)", " (2)", ... before the extension on collision, mirroring MediaStore's
 * auto-rename so the legacy (pre-Android 10) path does not overwrite an existing
 * download. Uses File.createNewFile() (an atomic create-if-absent) rather than a
 * check-then-create, so a racing writer cannot cause an overwrite.
 */
private fun uniqueDownloadFile(dir: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""

    var candidate = File(dir, name)
    var i = 1
    while (!candidate.createNewFile()) {
        val suffix = " ($i)"
        // Keep the suffixed name within the filesystem's 255-byte limit by
        // trimming the base; without this a near-max-length name could exceed
        // the limit once the collision suffix is appended.
        val room = MAX_FILENAME_BYTES - utf8Len(suffix) - utf8Len(ext)
        candidate = File(dir, trimToBytes(base, room) + suffix + ext)
        i++
    }
    return candidate
}

private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/**
 * Trims s to at most maxBytes UTF-8 bytes without splitting a Unicode code
 * point (so the result is always valid).
 */
private fun trimToBytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (utf8Len(s) <= maxBytes) return s

    val sb = StringBuilder()
    var bytes = 0
    val it = s.codePoints().iterator()
    while (it.hasNext()) {
        val chunk = String(Character.toChars(it.nextInt()))
        val chunkBytes = utf8Len(chunk)
        if (bytes + chunkBytes > maxBytes) break
        sb.append(chunk)
        bytes += chunkBytes
    }
    return sb.toString()
}

private fun Context.copyToDownloadsViaMediaStore(
    name: String,
    sourcePath: String,
    mimeType: String,
    size: Long
): Uri {
    val contentValues = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeType)
        put(MediaStore.Downloads.SIZE, size)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    val resolver = contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        ?: throw IllegalStateException("Failed to create MediaStore entry for Downloads")

    try {
        val outputStream = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("Failed to open output stream for Downloads")

        outputStream.use { out ->
            FileInputStream(sourcePath).use { input ->
                input.copyTo(out)
            }
        }

        // Mark as complete
        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        return uri
    } catch (e: Exception) {
        // Delete the incomplete file
        resolver.delete(uri, null, null)
        throw e
    }
}

private fun Context.copyToDownloadsLegacy(
    name: String,
    sourcePath: String,
    mimeType: String,
    size: Long
): Uri {
    // Copy to public Downloads directory
    @Suppress("DEPRECATION")
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    downloadsDir.mkdirs()
    // Pick a non-colliding name so an existing download is not overwritten
    // (MediaStore does this automatically on Android 10+).
    val destFile = uniqueDownloadFile(downloadsDir, name)

    try {
        FileInputStream(sourcePath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        // uniqueDownloadFile already created (reserved) destFile; remove the
        // 0-byte/partial file so a failed copy doesn't leave junk in Downloads.
        destFile.delete()
        throw e
    }

    // Register with DownloadManager
    val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    @Suppress("DEPRECATION")
    downloadManager.addCompletedDownload(
        destFile.name,
        "Received via Wormhole William",
        true,
        mimeType,
        destFile.absolutePath,
        size,
        true
    )

    return Uri.fromFile(destFile)
}

private fun Context.showDownloadCompleteNotification(name: String, uri: Uri, mimeType: String) {
    // Create notification channel for Android O+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Wormhole file transfer notifications"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    // Create intent to open the file
    val openIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val pendingIntent = PendingIntent.getActivity(
        this,
        0,
        openIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Download complete")
        .setContentText(name)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    try {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        // Notification permission not granted, ignore
    }
}

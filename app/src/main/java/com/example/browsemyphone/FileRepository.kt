package com.example.browsemyphone

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

enum class MediaType { IMAGES, AUDIO, VIDEO }

data class StorageVolumeInfo(
    val label: String,
    val path: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val removable: Boolean
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

object FileRepository {

    /** Detects mounted volumes (internal + any SD card / USB) with their capacity stats. */
    fun getStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val result = mutableListOf<StorageVolumeInfo>()
        context.getExternalFilesDirs(null).forEachIndexed { index, file ->
            if (file == null) return@forEachIndexed
            val abs = file.absolutePath
            val root = if (abs.contains("/Android/")) abs.substringBefore("/Android/") else abs
            runCatching {
                val stat = StatFs(root)
                result.add(
                    StorageVolumeInfo(
                        label = if (index == 0) "Internal storage" else "SD card / USB",
                        path = root,
                        totalBytes = stat.totalBytes,
                        freeBytes = stat.availableBytes,
                        removable = index != 0
                    )
                )
            }
        }
        if (result.isEmpty()) {
            val root = Environment.getExternalStorageDirectory()
            runCatching {
                val stat = StatFs(root.absolutePath)
                result.add(
                    StorageVolumeInfo(
                        "Internal storage", root.absolutePath,
                        stat.totalBytes, stat.availableBytes, false
                    )
                )
            }
        }
        return result
    }

    /** Lists a directory: folders first, then files, each alphabetical. */
    fun listDirectory(path: String): List<FileEntry> {
        val files = File(path).listFiles() ?: return emptyList()
        return files.map { f ->
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isDirectory) 0L else f.length(),
                lastModified = f.lastModified()
            )
        }.sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )
    }

    /** Walks the given roots and returns the biggest files above [minBytes], largest first. */
    suspend fun findLargeFiles(roots: List<String>, minBytes: Long, limit: Int): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val results = ArrayList<FileEntry>()
            val stack = ArrayDeque<File>()
            roots.forEach { stack.addLast(File(it)) }
            while (stack.isNotEmpty()) {
                val dir = stack.removeLast()
                val abs = dir.absolutePath
                // These are not readable even with all-files access.
                if (abs.endsWith("/Android/data") || abs.endsWith("/Android/obb")) continue
                val children = dir.listFiles() ?: continue
                for (c in children) {
                    if (c.isDirectory) {
                        stack.addLast(c)
                    } else if (c.length() >= minBytes) {
                        results.add(FileEntry(c.name, c.absolutePath, false, c.length(), c.lastModified()))
                    }
                }
            }
            results.sortByDescending { it.sizeBytes }
            if (results.size > limit) ArrayList(results.subList(0, limit)) else results
        }

    /** Queries the MediaStore for images / audio / video across all volumes. */
    fun queryMedia(context: Context, type: MediaType): List<FileEntry> {
        val collection = when (type) {
            MediaType.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val list = mutableListOf<FileEntry>()
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { c ->
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                list.add(
                    FileEntry(
                        name = c.getString(nameCol) ?: "Unknown",
                        path = c.getString(dataCol) ?: "",
                        isDirectory = false,
                        sizeBytes = c.getLong(sizeCol),
                        lastModified = c.getLong(dateCol) * 1000L
                    )
                )
            }
        }
        return list
    }

    /** Opens a file with whatever app can handle it, via a FileProvider content URI. */
    fun openFile(context: Context, entry: FileEntry) {
        runCatching {
            val file = File(entry.path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = context.contentResolver.getType(uri) ?: guessMime(entry.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        }.onFailure {
            Toast.makeText(context, "Can't open this file: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / 1024.0.pow(group.toDouble()), units[group])
}

fun formatDate(millis: Long): String {
    if (millis <= 0) return ""
    return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
}

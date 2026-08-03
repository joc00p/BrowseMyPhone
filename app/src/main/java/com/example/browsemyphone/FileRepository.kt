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

enum class ClipMode { COPY, MOVE }

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

data class FileProperties(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val itemCount: Int,
    val lastModified: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
    val hidden: Boolean
)

/** Result of a file operation: [success] plus a short message suitable for a toast. */
data class OpResult(val success: Boolean, val message: String)

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

    // ---- File operations -------------------------------------------------

    suspend fun delete(path: String): OpResult = withContext(Dispatchers.IO) {
        runCatching {
            if (File(path).deleteRecursively()) OpResult(true, "Deleted")
            else OpResult(false, "Delete failed")
        }.getOrElse { OpResult(false, "Delete failed: ${it.message}") }
    }

    suspend fun rename(path: String, newName: String): OpResult = withContext(Dispatchers.IO) {
        runCatching {
            val name = newName.trim()
            if (name.isEmpty()) return@runCatching OpResult(false, "Name can't be empty")
            if (name.contains('/')) return@runCatching OpResult(false, "Name can't contain '/'")
            val f = File(path)
            val parent = f.parentFile ?: return@runCatching OpResult(false, "No parent folder")
            val target = File(parent, name)
            if (target.exists()) return@runCatching OpResult(false, "\"$name\" already exists")
            if (f.renameTo(target)) OpResult(true, "Renamed") else OpResult(false, "Rename failed")
        }.getOrElse { OpResult(false, "Rename failed: ${it.message}") }
    }

    suspend fun copyInto(sourcePath: String, destDirPath: String): OpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(sourcePath)
                val destDir = File(destDirPath)
                if (!src.exists()) return@runCatching OpResult(false, "Source no longer exists")
                if (!destDir.isDirectory) return@runCatching OpResult(false, "Destination isn't a folder")
                if (src.isDirectory && isInside(destDir, src)) {
                    return@runCatching OpResult(false, "Can't copy a folder into itself")
                }
                val dest = uniqueDestination(destDir, src.name)
                src.copyRecursively(dest, overwrite = false)
                OpResult(true, "Copied to \"${destDir.name}\"")
            }.getOrElse { OpResult(false, "Copy failed: ${it.message}") }
        }

    suspend fun moveInto(sourcePath: String, destDirPath: String): OpResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(sourcePath)
                val destDir = File(destDirPath)
                if (!src.exists()) return@runCatching OpResult(false, "Source no longer exists")
                if (!destDir.isDirectory) return@runCatching OpResult(false, "Destination isn't a folder")
                if (src.isDirectory && isInside(destDir, src)) {
                    return@runCatching OpResult(false, "Can't move a folder into itself")
                }
                if (src.parentFile?.absolutePath == destDir.absolutePath) {
                    return@runCatching OpResult(false, "Already in this folder")
                }
                val dest = uniqueDestination(destDir, src.name)
                if (src.renameTo(dest)) return@runCatching OpResult(true, "Moved to \"${destDir.name}\"")
                // Cross-volume fallback: copy then delete the original.
                src.copyRecursively(dest, overwrite = false)
                if (src.deleteRecursively()) OpResult(true, "Moved to \"${destDir.name}\"")
                else OpResult(false, "Copied, but couldn't remove the original")
            }.getOrElse { OpResult(false, "Move failed: ${it.message}") }
        }

    suspend fun computeProperties(path: String): FileProperties = withContext(Dispatchers.IO) {
        val f = File(path)
        val size = if (f.isDirectory) {
            f.walkTopDown().filter { it.isFile }.fold(0L) { acc, file -> acc + file.length() }
        } else {
            f.length()
        }
        val count = if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0
        FileProperties(
            name = f.name,
            path = f.absolutePath,
            isDirectory = f.isDirectory,
            sizeBytes = size,
            itemCount = count,
            lastModified = f.lastModified(),
            canRead = f.canRead(),
            canWrite = f.canWrite(),
            hidden = f.isHidden
        )
    }

    private fun isInside(dir: File, ancestor: File): Boolean {
        val a = ancestor.absolutePath
        val d = dir.absolutePath
        return d == a || d.startsWith("$a/")
    }

    /** Returns a destination file inside [destDir] that doesn't clash, appending " (n)" if needed. */
    private fun uniqueDestination(destDir: File, name: String): File {
        var candidate = File(destDir, name)
        if (!candidate.exists()) return candidate
        val hasExt = name.contains('.') && !name.startsWith('.')
        val base = if (hasExt) name.substringBeforeLast('.') else name
        val ext = if (hasExt) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(destDir, "$base ($i)$ext")
            i++
        }
        return candidate
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
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
}

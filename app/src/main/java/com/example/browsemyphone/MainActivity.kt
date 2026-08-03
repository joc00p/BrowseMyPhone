@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.browsemyphone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.browsemyphone.ui.theme.BrowseMyPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrowseMyPhoneTheme {
                AppRoot()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Navigation & shared state
// ---------------------------------------------------------------------------

sealed interface Screen {
    val title: String

    data object Home : Screen {
        override val title = "Browse My Phone"
    }

    data class Browse(val path: String, override val title: String) : Screen

    data class Media(val type: MediaType, override val title: String) : Screen

    data object BigFiles : Screen {
        override val title = "Big files"
    }
}

/** A pending copy/move operation, shown as a Paste bar when browsing a folder. */
data class Clipboard(val entry: FileEntry, val mode: ClipMode)

private enum class DialogKind { RENAME, DELETE, PROPERTIES }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val accessState = rememberStorageAccessState()

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { accessState.value = hasStorageAccess(context) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { accessState.value = hasStorageAccess(context) }

    val requestAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null)
            )
            runCatching { settingsLauncher.launch(intent) }.onFailure {
                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = backStack.last()
    var clipboard by remember { mutableStateOf<Clipboard?>(null) }

    BackHandler(enabled = backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

    val browseTo: (String, String) -> Unit = { path, label ->
        backStack.add(Screen.Browse(path, label.ifEmpty { path }))
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (backStack.size > 1) {
                        IconButton(onClick = { backStack.removeAt(backStack.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (val screen = current) {
                is Screen.Home -> HomeScreen(
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    onCategory = { backStack.add(it) },
                    onBrowseVolume = { vol -> browseTo(vol.path, vol.label) }
                )

                is Screen.Browse -> BrowseScreen(
                    path = screen.path,
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    clipboard = clipboard,
                    onSetClipboard = { clipboard = it },
                    onUp = { parent -> browseTo(parent, File(parent).name) },
                    onOpenFolder = { browseTo(it.path, it.name) },
                    onOpenFile = { FileRepository.openFile(context, it) }
                )

                is Screen.Media -> MediaListScreen(
                    type = screen.type,
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    clipboard = clipboard,
                    onSetClipboard = { clipboard = it },
                    onOpenFile = { FileRepository.openFile(context, it) }
                )

                is Screen.BigFiles -> BigFilesScreen(
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    clipboard = clipboard,
                    onSetClipboard = { clipboard = it },
                    onOpenFile = { FileRepository.openFile(context, it) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
fun HomeScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onCategory: (Screen) -> Unit,
    onBrowseVolume: (StorageVolumeInfo) -> Unit
) {
    val context = LocalContext.current
    val volumes by produceState(initialValue = emptyList<StorageVolumeInfo>(), hasAccess) {
        value = withContext(Dispatchers.IO) { FileRepository.getStorageVolumes(context) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Browse by category",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CategoryButton(Icons.Filled.Image, "Images", Modifier.weight(1f)) {
                onCategory(Screen.Media(MediaType.IMAGES, "Images"))
            }
            CategoryButton(Icons.Filled.AudioFile, "Audio", Modifier.weight(1f)) {
                onCategory(Screen.Media(MediaType.AUDIO, "Audio"))
            }
            CategoryButton(Icons.Filled.Movie, "Video", Modifier.weight(1f)) {
                onCategory(Screen.Media(MediaType.VIDEO, "Video"))
            }
            CategoryButton(Icons.Filled.DataUsage, "Big files", Modifier.weight(1f)) {
                onCategory(Screen.BigFiles)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (!hasAccess) {
            AccessBanner(onRequestAccess)
            Spacer(Modifier.height(20.dp))
        }

        Text(
            "Storage — tap to browse files",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        volumes.forEach { vol ->
            StorageCard(vol) { onBrowseVolume(vol) }
            Spacer(Modifier.height(12.dp))
        }
        if (volumes.isEmpty()) {
            Text("No storage detected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CategoryButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun StorageCard(vol: StorageVolumeInfo, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (vol.removable) Icons.Filled.SdCard else Icons.Filled.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(vol.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = "Browse",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { vol.usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatSize(vol.usedBytes)} used of ${formatSize(vol.totalBytes)}  •  ${formatSize(vol.freeBytes)} free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AccessBanner(onRequestAccess: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Storage access needed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "To browse your files and folders, grant this app access to storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestAccess) { Text("Grant access") }
        }
    }
}

// ---------------------------------------------------------------------------
// Browse / Media / Big files
// ---------------------------------------------------------------------------

@Composable
fun BrowseScreen(
    path: String,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    clipboard: Clipboard?,
    onSetClipboard: (Clipboard?) -> Unit,
    onUp: (String) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    var refreshKey by remember { mutableStateOf(0) }
    val items by produceState<List<FileEntry>?>(initialValue = null, path, refreshKey) {
        value = withContext(Dispatchers.IO) { FileRepository.listDirectory(path) }
    }
    Column(Modifier.fillMaxSize()) {
        BrowsePathBar(path, onUp)
        val list = items
        when {
            list == null -> LoadingState()
            list.isEmpty() && clipboard == null -> EmptyState("This folder is empty")
            else -> FileListWithActions(
                modifier = Modifier.weight(1f),
                items = list ?: emptyList(),
                currentDir = path,
                clipboard = clipboard,
                onSetClipboard = onSetClipboard,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
                onChanged = { refreshKey++ }
            )
        }
    }
}

@Composable
fun MediaListScreen(
    type: MediaType,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    clipboard: Clipboard?,
    onSetClipboard: (Clipboard?) -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val items by produceState<List<FileEntry>?>(initialValue = null, type, refreshKey) {
        value = withContext(Dispatchers.IO) { FileRepository.queryMedia(context, type) }
    }
    val list = items
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState("No files found")
        else -> FileListWithActions(
            modifier = Modifier.fillMaxSize(),
            items = list,
            currentDir = null,
            clipboard = clipboard,
            onSetClipboard = onSetClipboard,
            onOpenFolder = {},
            onOpenFile = onOpenFile,
            onChanged = { refreshKey++ }
        )
    }
}

@Composable
fun BigFilesScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    clipboard: Clipboard?,
    onSetClipboard: (Clipboard?) -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    val items by produceState<List<FileEntry>?>(initialValue = null, refreshKey) {
        val roots = FileRepository.getStorageVolumes(context).map { it.path }
            .ifEmpty { listOf(Environment.getExternalStorageDirectory().absolutePath) }
        value = FileRepository.findLargeFiles(roots, 100L * 1024 * 1024, 200)
    }
    val list = items
    Column(Modifier.fillMaxSize()) {
        InfoBar("Files larger than 100 MB")
        when {
            list == null -> LoadingState("Scanning storage…")
            list.isEmpty() -> EmptyState("No large files found")
            else -> FileListWithActions(
                modifier = Modifier.weight(1f),
                items = list,
                currentDir = null,
                clipboard = clipboard,
                onSetClipboard = onSetClipboard,
                onOpenFolder = {},
                onOpenFile = onOpenFile,
                onChanged = { refreshKey++ }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// File list + per-item actions (copy / move / rename / delete / properties)
// ---------------------------------------------------------------------------

@Composable
fun FileListWithActions(
    modifier: Modifier = Modifier,
    items: List<FileEntry>,
    currentDir: String?,
    clipboard: Clipboard?,
    onSetClipboard: (Clipboard?) -> Unit,
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_SHORT).show()

    var sheetTarget by remember { mutableStateOf<FileEntry?>(null) }
    var dialogKind by remember { mutableStateOf<DialogKind?>(null) }
    var dialogTarget by remember { mutableStateOf<FileEntry?>(null) }

    Column(modifier) {
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(items, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    onClick = { if (entry.isDirectory) onOpenFolder(entry) else onOpenFile(entry) },
                    onMore = { sheetTarget = entry }
                )
                HorizontalDivider()
            }
        }
        if (currentDir != null && clipboard != null) {
            val dir: String = currentDir
            val clip: Clipboard = clipboard
            PasteBar(
                clipboard = clip,
                onCancel = { onSetClipboard(null) },
                onPaste = {
                    scope.launch {
                        val r = if (clip.mode == ClipMode.COPY) {
                            FileRepository.copyInto(clip.entry.path, dir)
                        } else {
                            FileRepository.moveInto(clip.entry.path, dir)
                        }
                        toast(r.message)
                        if (r.success) {
                            onSetClipboard(null)
                            onChanged()
                        }
                    }
                }
            )
        }
    }

    val target = sheetTarget
    if (target != null) {
        ModalBottomSheet(onDismissRequest = { sheetTarget = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                HorizontalDivider()
                if (!target.isDirectory) {
                    ActionRow(Icons.AutoMirrored.Filled.OpenInNew, "Open") {
                        sheetTarget = null
                        onOpenFile(target)
                    }
                }
                ActionRow(Icons.Filled.ContentCopy, "Copy") {
                    onSetClipboard(Clipboard(target, ClipMode.COPY))
                    sheetTarget = null
                    toast("Copied. Open a folder and tap Paste.")
                }
                ActionRow(Icons.Filled.DriveFileMove, "Move") {
                    onSetClipboard(Clipboard(target, ClipMode.MOVE))
                    sheetTarget = null
                    toast("Ready to move. Open a folder and tap Paste.")
                }
                ActionRow(Icons.Filled.Edit, "Rename") {
                    dialogTarget = target
                    dialogKind = DialogKind.RENAME
                    sheetTarget = null
                }
                ActionRow(Icons.Filled.Delete, "Delete") {
                    dialogTarget = target
                    dialogKind = DialogKind.DELETE
                    sheetTarget = null
                }
                ActionRow(Icons.Filled.Info, "Properties") {
                    dialogTarget = target
                    dialogKind = DialogKind.PROPERTIES
                    sheetTarget = null
                }
            }
        }
    }

    val dTarget = dialogTarget
    when (dialogKind) {
        DialogKind.RENAME -> if (dTarget != null) {
            var text by remember(dTarget) { mutableStateOf(dTarget.name) }
            AlertDialog(
                onDismissRequest = { dialogKind = null },
                title = { Text("Rename") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("New name") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val r = FileRepository.rename(dTarget.path, text)
                            toast(r.message)
                            if (r.success) onChanged()
                        }
                        dialogKind = null
                    }) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { dialogKind = null }) { Text("Cancel") }
                }
            )
        }

        DialogKind.DELETE -> if (dTarget != null) {
            AlertDialog(
                onDismissRequest = { dialogKind = null },
                icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                title = { Text("Delete?") },
                text = {
                    Text(
                        if (dTarget.isDirectory) {
                            "Delete the folder \"${dTarget.name}\" and everything inside it? This can't be undone."
                        } else {
                            "Delete \"${dTarget.name}\"? This can't be undone."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            val r = FileRepository.delete(dTarget.path)
                            toast(r.message)
                            if (r.success) onChanged()
                        }
                        dialogKind = null
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { dialogKind = null }) { Text("Cancel") }
                }
            )
        }

        DialogKind.PROPERTIES -> if (dTarget != null) {
            val props by produceState<FileProperties?>(initialValue = null, dTarget) {
                value = FileRepository.computeProperties(dTarget.path)
            }
            AlertDialog(
                onDismissRequest = { dialogKind = null },
                title = { Text("Properties") },
                text = {
                    val p = props
                    if (p == null) Text("Calculating…") else PropertiesContent(p)
                },
                confirmButton = {
                    TextButton(onClick = { dialogKind = null }) { Text("Close") }
                }
            )
        }

        null -> {}
    }
}

@Composable
fun FileRow(entry: FileEntry, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            iconFor(entry),
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(2.dp))
            val subtitle = if (entry.isDirectory) "Folder" else buildString {
                append(formatSize(entry.sizeBytes))
                val d = formatDate(entry.lastModified)
                if (d.isNotEmpty()) append("  •  $d")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PasteBar(clipboard: Clipboard, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (clipboard.mode == ClipMode.COPY) Icons.Filled.ContentCopy else Icons.Filled.DriveFileMove,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (clipboard.mode == ClipMode.COPY) "Copy here" else "Move here",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    clipboard.entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onPaste) { Text("Paste") }
        }
    }
}

@Composable
fun PropertiesContent(p: FileProperties) {
    Column {
        PropRow("Name", p.name)
        PropRow("Type", if (p.isDirectory) "Folder" else "File")
        PropRow("Size", formatSize(p.sizeBytes))
        if (p.isDirectory) PropRow("Items", p.itemCount.toString())
        PropRow("Location", p.path)
        PropRow("Modified", formatDate(p.lastModified).ifEmpty { "—" })
        PropRow(
            "Access",
            (if (p.canRead) "read" else "no read") + " / " + (if (p.canWrite) "write" else "read-only")
        )
    }
}

@Composable
fun PropRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces
// ---------------------------------------------------------------------------

@Composable
fun BrowsePathBar(path: String, onUp: (String) -> Unit) {
    val parent = remember(path) { File(path).parent }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { parent?.let(onUp) }, enabled = parent != null) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one level")
            }
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun InfoBar(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

fun iconFor(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Filled.Folder
    return when (entry.name.substringAfterLast('.', "").lowercase(Locale.getDefault())) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic" -> Icons.Filled.Image
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> Icons.Filled.AudioFile
        "mp4", "mkv", "avi", "mov", "wmv", "webm" -> Icons.Filled.Movie
        "pdf" -> Icons.Filled.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Filled.FolderZip
        "apk" -> Icons.Filled.Android
        "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx" -> Icons.Filled.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@Composable
fun LoadingState(message: String = "Loading…") {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PermissionNotice(onRequestAccess: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("Storage access is required to view this.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequestAccess) { Text("Grant access") }
    }
}

// ---------------------------------------------------------------------------
// Permission helpers
// ---------------------------------------------------------------------------

fun hasStorageAccess(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun rememberStorageAccessState(): MutableState<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(hasStorageAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = hasStorageAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

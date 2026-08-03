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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext
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
// Navigation
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

@OptIn(ExperimentalMaterial3Api::class)
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
    BackHandler(enabled = backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

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
                    onBrowseVolume = { vol -> backStack.add(Screen.Browse(vol.path, vol.label)) }
                )

                is Screen.Browse -> BrowseScreen(
                    path = screen.path,
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    onOpenFolder = { backStack.add(Screen.Browse(it.path, it.name)) },
                    onOpenFile = { FileRepository.openFile(context, it) }
                )

                is Screen.Media -> MediaListScreen(
                    type = screen.type,
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
                    onOpenFile = { FileRepository.openFile(context, it) }
                )

                is Screen.BigFiles -> BigFilesScreen(
                    hasAccess = accessState.value,
                    onRequestAccess = requestAccess,
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
            "Storage",
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
    onOpenFolder: (FileEntry) -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    val items by produceState<List<FileEntry>?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { FileRepository.listDirectory(path) }
    }
    Column(Modifier.fillMaxSize()) {
        PathBar(path)
        val list = items
        when {
            list == null -> LoadingState()
            list.isEmpty() -> EmptyState("This folder is empty")
            else -> FileList(list, onFolderClick = onOpenFolder, onFileClick = onOpenFile)
        }
    }
}

@Composable
fun MediaListScreen(
    type: MediaType,
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    val context = LocalContext.current
    val items by produceState<List<FileEntry>?>(initialValue = null, type) {
        value = withContext(Dispatchers.IO) { FileRepository.queryMedia(context, type) }
    }
    val list = items
    when {
        list == null -> LoadingState()
        list.isEmpty() -> EmptyState("No files found")
        else -> FileList(list, onFolderClick = {}, onFileClick = onOpenFile)
    }
}

@Composable
fun BigFilesScreen(
    hasAccess: Boolean,
    onRequestAccess: () -> Unit,
    onOpenFile: (FileEntry) -> Unit
) {
    if (!hasAccess) {
        PermissionNotice(onRequestAccess)
        return
    }
    val context = LocalContext.current
    val items by produceState<List<FileEntry>?>(initialValue = null) {
        val roots = FileRepository.getStorageVolumes(context).map { it.path }
            .ifEmpty { listOf(Environment.getExternalStorageDirectory().absolutePath) }
        value = FileRepository.findLargeFiles(roots, 100L * 1024 * 1024, 200)
    }
    val list = items
    Column(Modifier.fillMaxSize()) {
        PathBar("Files larger than 100 MB")
        when {
            list == null -> LoadingState("Scanning storage…")
            list.isEmpty() -> EmptyState("No large files found")
            else -> FileList(list, onFolderClick = {}, onFileClick = onOpenFile)
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
fun PathBar(text: String) {
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

@Composable
fun FileList(
    items: List<FileEntry>,
    onFolderClick: (FileEntry) -> Unit,
    onFileClick: (FileEntry) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.path }) { entry ->
            FileRow(entry) {
                if (entry.isDirectory) onFolderClick(entry) else onFileClick(entry)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        if (entry.isDirectory) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

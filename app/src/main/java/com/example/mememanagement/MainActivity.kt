package com.example.mememanagement

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.size.Size
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF6200EE), secondary = Color(0xFF03DAC6))
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
                    MemeApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

data class StickerRecord(val folderName: String, val url: String, val time: Long)
data class AppInfo(val packageName: String, val label: String, val icon: Drawable)

@Suppress("DEPRECATION")
fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) return true
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun MemeApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("meme_prefs", Context.MODE_PRIVATE) }
    val gson = Gson()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val activity = context as? MainActivity

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    var stickerPackages by remember { mutableStateOf(mapOf<String, List<File>>()) }
    var orderedPackageNames by remember {
        val json = prefs.getString("folder_order", "[]")
        val type = object : TypeToken<List<String>>() {}.type
        mutableStateOf(gson.fromJson<List<String>>(json, type) ?: emptyList())
    }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }

    var currentScreen by remember { mutableStateOf("home") }
    var urlText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var viewingImage by remember { mutableStateOf<File?>(null) }

    var gridSize by remember { mutableStateOf(prefs.getFloat("grid_size", 90f)) }
    var isFloatingEnabled by remember { mutableStateOf(isServiceRunning(context, FloatingService::class.java)) }
    var enableNotification by remember { mutableStateOf(prefs.getBoolean("enable_notify", true)) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf<File?>(null) }
    var showDeleteImageDialog by remember { mutableStateOf<File?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf<Pair<File, String>?>(null) }
    var duplicateRenameText by remember { mutableStateOf("") }

    var isProcessing by remember { mutableStateOf(false) }
    var pinnedFolders by remember {
        val json = prefs.getString("pinned_folders_list", "[]")
        val type = object : TypeToken<List<String>>() {}.type
        mutableStateOf(gson.fromJson<List<String>>(json, type) ?: emptyList())
    }
    val defaultApps = setOf("com.tencent.mm", "com.tencent.mobileqq")
    var pinnedPackages by remember { mutableStateOf(prefs.getStringSet("pinned_apps", defaultApps) ?: defaultApps) }
    var downloadHistory by remember {
        val json = prefs.getString("download_history", "[]")
        val type = object : TypeToken<List<StickerRecord>>() {}.type
        mutableStateOf(gson.fromJson<List<StickerRecord>>(json, type) ?: emptyList())
    }

    val stickersRootDir = File(context.filesDir, "stickers")
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameText by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    // --- 逻辑函数 ---
    fun updateOrder(newList: List<String>) {
        orderedPackageNames = ArrayList(newList)
        prefs.edit { putString("folder_order", gson.toJson(newList)) }
    }

    fun updatePinnedApps(newSet: Set<String>) {
        pinnedPackages = newSet
        prefs.edit { putStringSet("pinned_apps", newSet) }
    }

    fun refreshStickers(keepTab: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (!stickersRootDir.exists()) stickersRootDir.mkdirs()
            if (stickersRootDir.exists()) {
                val allFolders = stickersRootDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                val currentOrder = orderedPackageNames.toMutableList()

                allFolders.forEach { folderName ->
                    if (!currentOrder.contains(folderName)) {
                        var insertIndex = 0
                        for (i in currentOrder.indices) {
                            if (pinnedFolders.contains(currentOrder[i])) {
                                insertIndex = i + 1
                            } else {
                                break
                            }
                        }
                        currentOrder.add(insertIndex.coerceAtMost(currentOrder.size), folderName)
                    }
                }
                currentOrder.retainAll(allFolders)

                val newMap = HashMap<String, List<File>>()
                allFolders.forEach { name ->
                    val folder = File(stickersRootDir, name)
                    val images = folder.walk()
                        .filter { it.isFile && (it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) || it.name.endsWith(".gif", true) || it.name.endsWith(".webp", true) || it.name.endsWith(".webm", true)) }
                        .sortedByDescending { it.lastModified() }
                        .toList()
                    newMap[name] = images
                }

                withContext(Dispatchers.Main) {
                    stickerPackages = newMap
                    updateOrder(currentOrder)
                    if (!keepTab && (selectedPackageName == null || !currentOrder.contains(selectedPackageName))) {
                        selectedPackageName = currentOrder.firstOrNull()
                    }
                }
            }
        }
    }

    fun createNewFolder(name: String) {
        val targetDir = File(stickersRootDir, name)
        if (!targetDir.exists()) targetDir.mkdirs()
        val newOrder = orderedPackageNames.toMutableList()
        if (!newOrder.contains(name)) newOrder.add(0, name)
        updateOrder(newOrder)
        refreshStickers(keepTab = false)
        selectedPackageName = name
        Toast.makeText(context, "✅ 创建成功", Toast.LENGTH_SHORT).show()
    }

    fun moveFolderUp(name: String) {
        val list = orderedPackageNames.toMutableList()
        val index = list.indexOf(name)
        if (index > 0) {
            Collections.swap(list, index, index - 1)
            updateOrder(list)
            refreshStickers(true)
        }
    }

    // ✨ 彻底修复置顶逻辑
    fun togglePinFolder(folderName: String) {
        val newPinned = pinnedFolders.toMutableList()
        val isPinnedNow = newPinned.contains(folderName)

        if (isPinnedNow) {
            // 如果已经置顶 -> 取消置顶
            newPinned.remove(folderName)
        } else {
            // 如果没置顶 -> 加入置顶
            newPinned.add(0, folderName)
        }
        pinnedFolders = newPinned
        prefs.edit { putString("pinned_folders_list", gson.toJson(newPinned)) }

        // 关键逻辑修正：只有在【执行了置顶操作】时，才强制把文件夹移到列表首位
        if (!isPinnedNow) {
            val currentList = orderedPackageNames.toMutableList()
            currentList.remove(folderName)
            currentList.add(0, folderName)
            updateOrder(currentList)
        }
        // 如果是【取消置顶】，我们不动它的位置，用户如果想往下排，可以下次刷新或手动移

        refreshStickers(true)
    }

    fun renameFolder(old: String, new: String) {
        if (File(stickersRootDir, old).renameTo(File(stickersRootDir, new))) {
            val list = orderedPackageNames.toMutableList()
            val index = list.indexOf(old)
            if (index != -1) list[index] = new
            if (pinnedFolders.contains(old)) {
                val p = pinnedFolders.toMutableList()
                p[p.indexOf(old)] = new
                pinnedFolders = p
                prefs.edit { putString("pinned_folders_list", gson.toJson(p)) }
            }
            updateOrder(list)
            refreshStickers(false)
            selectedPackageName = new
        }
    }

    fun saveHistory(newRecord: StickerRecord) {
        if (newRecord.url.isNotEmpty() && (newRecord.url.startsWith("http", ignoreCase = true))) {
            val newList = downloadHistory.toMutableList()
            newList.removeAll { it.folderName == newRecord.folderName }
            newList.add(0, newRecord)
            downloadHistory = newList
            prefs.edit { putString("download_history", gson.toJson(newList)) }
        }
    }

    fun processDownload(f: File, n: String, u: String, o: Boolean = false) {
        if (!isZipValid(f)) {
            if (f.exists()) f.delete()
            Toast.makeText(context, "❌ 无效文件: 包内未检测到图片", Toast.LENGTH_SHORT).show()
            return
        }

        val t = File(stickersRootDir, n)
        if (o && t.exists()) t.deleteRecursively()
        if (!t.exists()) t.mkdirs()
        unzip(f, t)
        if (f.exists()) f.delete()
        saveHistory(StickerRecord(n, u, System.currentTimeMillis()))

        val l = orderedPackageNames.toMutableList()
        if (!l.contains(n)) {
            l.add(0, n)
            updateOrder(l)
        }
        refreshStickers(false)
        selectedPackageName = n
        urlText = ""
        Toast.makeText(context, "✅ 导入成功: $n", Toast.LENGTH_SHORT).show()
    }

    fun handleDownload(url: String) {
        if (url.isEmpty()) return
        keyboardController?.hide()
        Toast.makeText(context, "下载中...", Toast.LENGTH_SHORT).show()
        downloadZip(url, context) { f ->
            val rawName = try {
                java.net.URLDecoder.decode(url.substringAfterLast("/"), "UTF-8").substringBeforeLast(".")
            } catch (_: Exception) {
                "Pack_${System.currentTimeMillis()}"
            }
            processDownload(f, rawName, url)
        }
    }

    fun exportBackup() {
        if (isProcessing) return
        isProcessing = true
        scope.launch(Dispatchers.IO) {
            try {
                val json = gson.toJson(downloadHistory)
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupFile = File(downloadDir, "meme_backup_${System.currentTimeMillis()}.json")
                FileWriter(backupFile).use { it.write(json) }
                withContext(Dispatchers.Main) { Toast.makeText(context, "✅ 备份成功: ${backupFile.name}", Toast.LENGTH_LONG).show(); isProcessing = false }
            } catch (_: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "备份失败", Toast.LENGTH_SHORT).show(); isProcessing = false } }
        }
    }

    fun exportPackageToZip(folderName: String) {
        if (isProcessing) return
        isProcessing = true
        Toast.makeText(context, "正在打包导出...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val sourceDir = File(stickersRootDir, folderName)
                val zipFile = File(context.cacheDir, "${folderName}_share.zip")
                if (zipFile.exists()) zipFile.delete()
                zipFolder(sourceDir, zipFile)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出表情包"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val input = context.contentResolver.openInputStream(it)
                val json = input?.bufferedReader().use { br -> br?.readText() }
                if (json != null) {
                    val type = object : TypeToken<List<StickerRecord>>() {}.type
                    val records: List<StickerRecord> = gson.fromJson(json, type)
                    val validRecords = records.filter { r -> r.url.startsWith("http", true) }

                    val newHistory = downloadHistory.toMutableList()
                    validRecords.forEach { r -> newHistory.removeAll{ h -> h.folderName == r.folderName }; newHistory.add(0, r) }
                    downloadHistory = newHistory
                    prefs.edit { putString("download_history", gson.toJson(newHistory)) }

                    Toast.makeText(context, "正在恢复 ${validRecords.size} 个有效记录...", Toast.LENGTH_LONG).show()
                    scope.launch(Dispatchers.IO) {
                        val semaphore = Semaphore(4)
                        validRecords.map { r -> async { semaphore.withPermit { val t = "temp_${r.folderName}.zip"; downloadAndUnzipSync(context, r.url, r.folderName, t) } } }.awaitAll()
                        withContext(Dispatchers.Main) { refreshStickers(false); Toast.makeText(context, "🎉 恢复完成！", Toast.LENGTH_LONG).show() }
                    }
                }
            } catch (_: Exception) { Toast.makeText(context, "文件解析失败", Toast.LENGTH_SHORT).show() }
        }
    }

    val importLocalZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val input = context.contentResolver.openInputStream(it)
                val tempFile = File(context.cacheDir, "local.zip")
                val out = FileOutputStream(tempFile)
                input?.copyTo(out)
                input?.close()
                out.close()
                val name = try {
                    val cursor = context.contentResolver.query(it, null, null, null, null)
                    var n: String? = null
                    if (cursor != null && cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) n = cursor.getString(idx)
                    }
                    cursor?.close()
                    n?.substringBeforeLast(".") ?: "LocalImport"
                } catch (_: Exception) { "LocalImport" }
                processDownload(tempFile, name, "")
            } catch (_: Exception) {}
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val pkg = selectedPackageName
            if (pkg != null) {
                context.contentResolver.openInputStream(it)?.use { input ->
                    File(stickersRootDir, pkg).let { dir ->
                        FileOutputStream(File(dir, "add_${System.currentTimeMillis()}.jpg")).use { out -> input.copyTo(out) }
                    }
                }
                refreshStickers(true)
            }
        }
    }

    fun getShareableApps(): List<AppInfo> {
        val i = Intent(Intent.ACTION_SEND).apply { type = "image/*" }
        val pm = context.packageManager
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct().mapNotNull {
            try {
                val a = pm.getApplicationInfo(it, 0)
                AppInfo(it, pm.getApplicationLabel(a).toString(), pm.getApplicationIcon(a))
            } catch (_: Exception) { null }
        }
    }

    fun shareToApp(f: File, p: String?) {
        val u = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            if (p != null) {
                when (p) {
                    "com.tencent.mm" -> i.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
                    "com.tencent.mobileqq" -> i.setClassName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity")
                    else -> i.setPackage(p)
                }
            }
            context.startActivity(i)
        } catch (_: Exception) {
            if (p != null) {
                val backup = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, u)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage(p)
                }
                try { context.startActivity(backup) } catch (_: Exception) {
                    context.startActivity(Intent.createChooser(backup, "分享"))
                }
            } else {
                context.startActivity(Intent.createChooser(i, "分享"))
            }
        }
    }

    fun toggleFloatingService(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "需悬浮窗权限", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                isFloatingEnabled = false
            } else {
                context.startService(Intent(context, FloatingService::class.java))
                isFloatingEnabled = true
            }
        } else {
            context.stopService(Intent(context, FloatingService::class.java))
            isFloatingEnabled = false
        }
        prefs.edit { putBoolean("enable_floating", isFloatingEnabled) }
    }

    fun toggleNotification(enable: Boolean) {
        enableNotification = enable
        prefs.edit { putBoolean("enable_notify", enable) }
        if (enable) showNotification(context) else cancelNotification(context)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it && enableNotification) showNotification(context) }

    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try { if (cursor != null && cursor.moveToFirst()) { val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index >= 0) result = cursor.getString(index) } } finally { cursor?.close() }
        }
        if (result == null) { result = uri.path; val cut = result?.lastIndexOf('/'); if (cut != -1) { result = result?.substring(cut!! + 1) } }
        return result?.substringBeforeLast(".") ?: "Import_${System.currentTimeMillis()}"
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isFloatingEnabled = isServiceRunning(context, FloatingService::class.java)
                refreshStickers()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        refreshStickers()

        scope.launch(Dispatchers.IO) {
            try {
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".zip")) {
                        if (System.currentTimeMillis() - file.lastModified() > 60 * 1000) {
                            file.delete()
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (prefs.getBoolean("enable_floating", false) && Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, FloatingService::class.java))
            isFloatingEnabled = true
        }
        if (enableNotification && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else if (enableNotification) {
            showNotification(context)
        }

        activity?.intent?.let { intent ->
            val action = intent.action
            val type = intent.type
            if ((Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) && type != null) {
                val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) {
                    try {
                        val name = getFileName(context, uri)
                        val input = context.contentResolver.openInputStream(uri)
                        val tempFile = File(context.cacheDir, "external_import.zip")
                        val out = FileOutputStream(tempFile)
                        input?.copyTo(out)
                        input?.close(); out.close()

                        val targetDir = File(stickersRootDir, name)
                        if (targetDir.exists()) {
                            duplicateRenameText = "${name}_copy"
                            showDuplicateDialog = tempFile to name
                        } else {
                            processDownload(tempFile, name, "")
                        }
                        activity.intent = null
                    } catch (e: Exception) {
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    BackHandler(enabled = currentScreen != "home" || isSearching || viewingImage != null) {
        when {
            viewingImage != null -> viewingImage = null
            isSearching -> { isSearching = false; searchQuery = "" }
            currentScreen != "home" -> currentScreen = "home"
        }
    }

    // --- 5. UI 渲染 ---
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentScreen == "home") {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(shadowElevation = 4.dp, color = Color.White, modifier = Modifier.zIndex(1f)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isSearching) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isSearching = false; searchQuery = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭") }
                                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("搜索表情包...") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            if (searchQuery.isNotEmpty()) {
                                val results = orderedPackageNames.filter { it.contains(searchQuery, ignoreCase = true) }
                                LazyColumn(modifier = Modifier.heightIn(max = 200.dp).padding(top = 8.dp)) {
                                    items(results) { pkg -> ListItem(headlineContent = { Text(pkg) }, modifier = Modifier.clickable { selectedPackageName = pkg; isSearching = false }, leadingContent = { Icon(Icons.Default.Folder, null) }); HorizontalDivider() }
                                }
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("表情包管理器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(onClick = { isSearching = true }) { Icon(Icons.Default.Search, "搜索") }
                                    IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, "设置") }
                                    IconButton(onClick = { currentScreen = "manage" }) { Icon(Icons.AutoMirrored.Filled.List, "管理") }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = urlText, onValueChange = { urlText = it }, label = { Text("ZIP 链接", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                                TextButton(onClick = { showCreateFolderDialog = true }) { Text("新建") }
                                TextButton(onClick = { importLocalZipLauncher.launch("application/zip") }) { Text("导入") }
                                Button(onClick = { handleDownload(urlText) }) { Text("下载") }
                            }
                        }
                    }
                }

                if (stickerPackages.isNotEmpty() && orderedPackageNames.isNotEmpty()) {
                    val validTabs = orderedPackageNames.filter { stickerPackages.containsKey(it) }
                    if (validTabs.isNotEmpty()) {
                        val selectedIndex = validTabs.indexOf(selectedPackageName).coerceAtLeast(0)
                        ScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            edgePadding = 16.dp,
                            containerColor = Color.White,
                            indicator = { p -> SecondaryIndicator(Modifier.tabIndicatorOffset(p[selectedIndex])) },
                            divider = { HorizontalDivider(color = Color(0xFFEEEEEE)) }
                        ) {
                            validTabs.forEachIndexed { i, name -> Tab(selected = selectedIndex == i, onClick = { selectedPackageName = name }, text = { Text(if(name.length>6) name.take(5)+".." else name) }) }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            val currentName = validTabs.getOrNull(selectedIndex)
                            val currentImages = stickerPackages[currentName] ?: emptyList()

                            if (currentImages.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无图片，点击右下角添加", color = Color.Gray) }
                            }

                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(gridSize.dp),
                                contentPadding = PaddingValues(bottom = 80.dp, top = 12.dp, start = 12.dp, end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(currentImages) { img ->
                                    StickerGridItem(
                                        file = img,
                                        imageLoader = imageLoader,
                                        onClick = { showShareSheet = img },
                                        onLongClick = { viewingImage = img }
                                    )
                                }
                            }

                            FloatingActionButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                                shape = CircleShape
                            ) { Icon(Icons.Default.Add, "添加") }
                        }
                    } else { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("无有效包") } }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("空空如也", color = Color.Gray)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showCreateFolderDialog = true }) { Text("新建表情包") }
                        }
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentScreen = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Text("管理 (${orderedPackageNames.size})", style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                LazyColumn {
                    items(orderedPackageNames) { folder ->
                        val isPinned = pinnedFolders.contains(folder)
                        // ✨ 保持你喜欢的 UI：文字换行，按钮紧凑
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = folder,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy((-4).dp)
                                ) {
                                    IconButton(onClick = { togglePinFolder(folder) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Filled.PushPin, "置顶", tint = if (isPinned) MaterialTheme.colorScheme.primary else Color.LightGray, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { exportPackageToZip(folder) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Share, "导出", tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { moveFolderUp(folder) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.VerticalAlignTop, "上移", tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { showRenameDialog = folder; renameText = folder }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Edit, "改名", tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                    IconButton(onClick = { File(stickersRootDir, folder).deleteRecursively(); val n=orderedPackageNames.toMutableList(); n.remove(folder); updateOrder(n); refreshStickers(false) }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Delete, "删除", tint = Color.Red, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        if (viewingImage != null) {
            PreviewDialog(
                file = viewingImage!!,
                imageLoader = imageLoader,
                onDismiss = { viewingImage = null },
                onMoveToFront = {
                    viewingImage?.setLastModified(System.currentTimeMillis())
                    refreshStickers(keepTab = true)
                    Toast.makeText(context, "已移到最前", Toast.LENGTH_SHORT).show()
                    viewingImage = null
                },
                onDelete = { showDeleteImageDialog = viewingImage }
            )
        }
    }

    if (showDeleteImageDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteImageDialog = null },
            title = { Text("删除") },
            text = { Text("确定删除这张图片吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteImageDialog?.delete()
                    refreshStickers(true)
                    viewingImage = null
                    showDeleteImageDialog = null
                }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteImageDialog = null }) { Text("取消") } }
        )
    }

    if (showDuplicateDialog != null) { val (t, n) = showDuplicateDialog!!; AlertDialog(onDismissRequest = { showDuplicateDialog = null }, title = { Text("名称冲突") }, text = { Column { Text("文件夹 \"$n\" 已存在。"); Spacer(modifier = Modifier.height(8.dp)); OutlinedTextField(value = duplicateRenameText, onValueChange = { duplicateRenameText = it }, label = { Text("新名称") }, singleLine = true) } }, confirmButton = { Button(onClick = { if (duplicateRenameText.isNotBlank() && !duplicateRenameText.contains("/")) { processDownload(t, duplicateRenameText, "", false); showDuplicateDialog = null } }) { Text("保存副本") } }, dismissButton = { TextButton(onClick = { processDownload(t, n, "", true); showDuplicateDialog = null }) { Text("覆盖旧的") } }) }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("设置") },
            text = {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("通知栏快捷入口")
                        Switch(checked = enableNotification, onCheckedChange = { toggleNotification(it) })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("悬浮窗快捷入口")
                        Switch(checked = isFloatingEnabled, onCheckedChange = { toggleFloatingService(it) })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("网格大小: ${gridSize.toInt()}dp")
                    Slider(value = gridSize, onValueChange = { gridSize = it; prefs.edit().putFloat("grid_size", it).apply() }, valueRange = 60f..180f)
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = { restoreBackupLauncher.launch("application/json"); showSettingsDialog = false }) { Text("导入恢复") }
                        Button(onClick = { exportBackup(); showSettingsDialog = false }, enabled = !isProcessing) { Text("导出备份") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("关闭") } }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("新建标签") },
            text = { OutlinedTextField(value = newFolderNameText, onValueChange = { newFolderNameText = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (newFolderNameText.isNotBlank() && !newFolderNameText.contains("/")) {
                        createNewFolder(newFolderNameText)
                        newFolderNameText = ""
                        showCreateFolderDialog = false
                    } else {
                        Toast.makeText(context, "名称无效", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("取消") } }
        )
    }

    if (showRenameDialog != null) { AlertDialog(onDismissRequest = { showRenameDialog = null }, title = { Text("重命名") }, text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("新名称") }, singleLine = true) }, confirmButton = { Button(onClick = { if (renameText.isNotBlank() && !renameText.contains("/")) { renameFolder(showRenameDialog!!, renameText); showRenameDialog = null } }) { Text("确定") } }, dismissButton = { TextButton(onClick = { showRenameDialog = null }) { Text("取消") } }) }

    if (showShareSheet != null) {
        ModalBottomSheet(onDismissRequest = { showShareSheet = null }, containerColor = Color.White) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("分享到...", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(pinnedPackages.toList()) { pkg ->
                        val pm = context.packageManager
                        val info = try { pm.getApplicationInfo(pkg, 0) } catch (e:Exception) { null }
                        if (info != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { shareToApp(showShareSheet!!, pkg); showShareSheet = null }) {
                                Image(painter = rememberAsyncImagePainter(pm.getApplicationIcon(info)), contentDescription = null, modifier = Modifier.size(50.dp))
                                Text(pm.getApplicationLabel(info).toString().take(4), fontSize = 12.sp)
                            }
                        }
                    }
                    item { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAddAppDialog = true }) { Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.LightGray), contentAlignment = Alignment.Center){Icon(Icons.Default.Add,null)}; Text("管理", fontSize = 12.sp) } }
                    item { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { shareToApp(showShareSheet!!, null); showShareSheet = null }) { Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.LightGray), contentAlignment = Alignment.Center){Icon(Icons.Default.Share,null)}; Text("更多", fontSize = 12.sp) } }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showAddAppDialog) {
        val allApps = remember { getShareableApps() }
        AlertDialog(
            onDismissRequest = { showAddAppDialog = false },
            title = { Text("编辑常用分享") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(300.dp)) {
                    items(allApps) { app ->
                        val isSelected = pinnedPackages.contains(app.packageName)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable {
                                    val newSet = pinnedPackages.toMutableSet()
                                    if (isSelected) newSet.remove(app.packageName) else newSet.add(app.packageName)
                                    updatePinnedApps(newSet)
                                }
                                .padding(8.dp)
                        ) {
                            Image(painter = rememberAsyncImagePainter(app.icon), contentDescription = null, modifier = Modifier.size(32.dp))
                            Text(app.label, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAddAppDialog = false }) { Text("完成") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerGridItem(file: File, imageLoader: ImageLoader, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .size(Size.ORIGINAL)
                        .crossfade(true)
                        .allowHardware(false)
                        .build(),
                    imageLoader = imageLoader
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun PreviewDialog(file: File, imageLoader: ImageLoader, onDismiss: () -> Unit, onMoveToFront: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth(0.6f).wrapContentHeight()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(file)
                            .crossfade(true)
                            .allowHardware(true)
                            .build(),
                        imageLoader = imageLoader
                    ),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().wrapContentHeight()
                )
                HorizontalDivider(color = Color(0xFFEEEEEE))
                Row(modifier = Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onMoveToFront), contentAlignment = Alignment.Center) { Text("移到前面", fontSize = 14.sp) }
                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = Color(0xFFEEEEEE))
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onDelete), contentAlignment = Alignment.Center) { Text("删除", fontSize = 14.sp, color = Color.Red) }
                }
            }
        }
    }
}

fun showNotification(context: Context) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel("meme_shortcut", "快捷启动", NotificationManager.IMPORTANCE_LOW)); nm.notify(1001, android.app.Notification.Builder(context, "meme_shortcut").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("表情包").setContentText("点击斗图").setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }, PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()) } }
fun cancelNotification(context: Context) { val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.cancel(1001) }
fun downloadZip(url: String, context: android.content.Context, onDownloadSuccess: (File) -> Unit) { val client = okhttp3.OkHttpClient(); Thread { try { val response = client.newCall(okhttp3.Request.Builder().url(url).build()).execute(); if (response.isSuccessful) { val destFile = File(context.cacheDir, "temp_sticker.zip"); FileOutputStream(destFile).use { it.write(response.body!!.bytes()) }; (context as android.app.Activity).runOnUiThread { onDownloadSuccess(destFile) } } else { (context as android.app.Activity).runOnUiThread { Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { (context as android.app.Activity).runOnUiThread { Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_SHORT).show() } } }.start() }

fun downloadAndUnzipSync(context: Context, url: String, targetName: String, tempName: String) {
    try {
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (response.isSuccessful) {
            val destFile = File(context.cacheDir, tempName)
            FileOutputStream(destFile).use { it.write(response.body!!.bytes()) }

            if (isZipValid(destFile)) {
                val targetDir = File(context.filesDir, "stickers/$targetName")
                if (!targetDir.exists()) targetDir.mkdirs()
                unzipSync(destFile, targetDir)
            }
            destFile.delete()
        }
    } catch (e: Exception) { e.printStackTrace() }
}

fun isZipValid(file: File): Boolean {
    var checkedCount = 0
    try {
        ZipInputStream(FileInputStream(file)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (name.endsWith(".jpg") || name.endsWith(".png") ||
                        name.endsWith(".gif") || name.endsWith(".webp") ||
                        name.endsWith(".webm")) {
                        return true
                    }
                    checkedCount++
                    if (checkedCount >= 3) return false
                }
                entry = zip.nextEntry
            }
        }
    } catch (_: Exception) { return false }
    return false
}

fun unzipSync(zipFile: File, targetDir: File) { try { ZipInputStream(java.io.FileInputStream(zipFile)).use { zip -> var entry = zip.nextEntry; while (entry != null) { val newFile = File(targetDir, entry.name); if (entry.isDirectory) newFile.mkdirs() else { newFile.parentFile?.mkdirs(); FileOutputStream(newFile).use { out -> zip.copyTo(out) } }; entry = zip.nextEntry } } } catch (e: Exception) { e.printStackTrace() } }
fun unzip(zipFile: File, targetDir: File) { unzipSync(zipFile, targetDir) }

fun zipFolder(sourceFolder: File, zipFile: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        sourceFolder.walk().filter { it.isFile }.forEach { file ->
            val entryName = file.name
            zos.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(file)).use { bis -> bis.copyTo(zos) }
            zos.closeEntry()
        }
    }
}

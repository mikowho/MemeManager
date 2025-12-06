package com.example.mememanagement

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.rememberAsyncImagePainter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.util.Collections
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF6200EE), secondary = Color(0xFF03DAC6))
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) { MemeApp() }
            }
        }
    }
}

data class StickerRecord(val folderName: String, val url: String, val time: Long)
data class AppInfo(val packageName: String, val label: String, val icon: Drawable)

@Suppress("DEPRECATION")
fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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

    var urlText by remember { mutableStateOf("") }

    // === 核心：有序列表 ===
    var orderedPackageNames by remember {
        val json = prefs.getString("folder_order", "[]")
        val type = object : TypeToken<List<String>>() {}.type
        mutableStateOf(gson.fromJson<List<String>>(json, type) ?: emptyList())
    }

    var stickerPackages by remember { mutableStateOf(mapOf<String, List<File>>()) }
    var selectedPackageName by remember { mutableStateOf<String?>(null) }
    var pinnedFolders by remember {
        val json = prefs.getString("pinned_folders_list", "[]")
        val type = object : TypeToken<List<String>>() {}.type
        mutableStateOf(gson.fromJson<List<String>>(json, type) ?: emptyList())
    }

    var enableNotification by remember { mutableStateOf(prefs.getBoolean("enable_notify", true)) }
    var isFloatingEnabled by remember { mutableStateOf(isServiceRunning(context, FloatingService::class.java)) }

    var currentScreen by remember { mutableStateOf("home") }
    var isProcessing by remember { mutableStateOf(false) }

    // 弹窗
    var showShareSheet by remember { mutableStateOf<File?>(null) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var showDeletePackageDialog by remember { mutableStateOf(false) }
    var showDeleteImageDialog by remember { mutableStateOf<File?>(null) }
    var showDuplicateDialog by remember { mutableStateOf<Pair<File, String>?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var duplicateRenameText by remember { mutableStateOf("") }

    // 新增：创建文件夹弹窗
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameText by remember { mutableStateOf("") }

    val defaultApps = setOf("com.tencent.mm", "com.tencent.mobileqq")
    var pinnedPackages by remember { mutableStateOf(prefs.getStringSet("pinned_apps", defaultApps) ?: defaultApps) }

    var downloadHistory by remember {
        val json = prefs.getString("download_history", "[]")
        val type = object : TypeToken<List<StickerRecord>>() {}.type
        mutableStateOf(gson.fromJson<List<StickerRecord>>(json, type) ?: emptyList())
    }

    val stickersRootDir = File(context.filesDir, "stickers")

    BackHandler(enabled = currentScreen != "home") { currentScreen = "home" }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isFloatingEnabled = isServiceRunning(context, FloatingService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toggleFloatingService(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
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
        prefs.edit().putBoolean("enable_floating", isFloatingEnabled).apply()
    }

    fun toggleNotification(enable: Boolean) {
        enableNotification = enable
        prefs.edit().putBoolean("enable_notify", enable).apply()
        if (enable) showNotification(context) else cancelNotification(context)
    }

    // === 核心修复 1：更新顺序 ===
    fun updateOrder(newList: List<String>) {
        orderedPackageNames = newList.toList()
        prefs.edit().putString("folder_order", gson.toJson(newList)).apply()
    }

    // === 核心修复 2：刷新逻辑 (严格按 orderedPackageNames 排序) ===
    fun refreshStickers(keepTab: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            if (!stickersRootDir.exists()) stickersRootDir.mkdirs()
            if (stickersRootDir.exists()) {
                val allFolders = stickersRootDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

                // 1. 拿到最新的顺序列表
                val currentOrder = orderedPackageNames.toMutableList()
                // 2. 移除不存在的
                currentOrder.retainAll(allFolders)
                // 3. 找出新增的
                val newFolders = allFolders.filter { !currentOrder.contains(it) }
                // 4. 新增的放到前面 (按时间倒序)
                currentOrder.addAll(0, newFolders.sortedByDescending { File(stickersRootDir, it).lastModified() })

                // 5. 构建 Map
                val newMap = HashMap<String, List<File>>()
                allFolders.forEach { name ->
                    val folder = File(stickersRootDir, name)
                    val images = folder.walk()
                        .filter { it.isFile && (it.name.endsWith(".jpg", true) || it.name.endsWith(".png", true) || it.name.endsWith(".gif", true) || it.name.endsWith(".webp", true)) }
                        .toList()
                    newMap[name] = images
                }

                withContext(Dispatchers.Main) {
                    stickerPackages = newMap
                    updateOrder(currentOrder) // 确保 State 更新

                    if (keepTab && selectedPackageName != null && currentOrder.contains(selectedPackageName)) {
                        // 保持
                    } else {
                        if (selectedPackageName == null || !currentOrder.contains(selectedPackageName)) {
                            selectedPackageName = currentOrder.firstOrNull()
                        }
                    }
                }
            }
        }
    }

    // === 核心修复 3：置顶逻辑 ===
    fun togglePinFolder(folderName: String) {
        val newPinned = pinnedFolders.toMutableList()
        val isPinned = newPinned.contains(folderName)

        if (isPinned) {
            // 取消置顶：不改变当前顺序，只是去掉标记
            newPinned.remove(folderName)
        } else {
            // 置顶：加标记，且移到列表最前
            newPinned.add(0, folderName)
            val newOrder = orderedPackageNames.toMutableList()
            newOrder.remove(folderName)
            newOrder.add(0, folderName)
            updateOrder(newOrder)
        }

        pinnedFolders = newPinned.toList()
        prefs.edit().putString("pinned_folders_list", gson.toJson(newPinned)).apply()

        // 刷新，保持 Tab 不变
        refreshStickers(keepTab = true)
    }

    // === 核心修复 4：上移逻辑 ===
    fun moveFolderUp(name: String) {
        val list = orderedPackageNames.toMutableList()
        val index = list.indexOf(name)
        if (index > 0) {
            Collections.swap(list, index, index - 1)
            updateOrder(list)
            // 刷新，保持 Tab 不变
            refreshStickers(keepTab = true)
        }
    }

    fun saveHistory(newRecord: StickerRecord) {
        if (newRecord.url.isNotEmpty()) {
            val newList = downloadHistory.toMutableList()
            newList.removeAll { it.folderName == newRecord.folderName }
            newList.add(0, newRecord)
            downloadHistory = newList
            prefs.edit().putString("download_history", gson.toJson(newList)).apply()
        }
    }

    fun processDownload(tempFile: File, targetName: String, sourceUrl: String, isOverride: Boolean = false) {
        val targetDir = File(stickersRootDir, targetName)
        if (isOverride && targetDir.exists()) targetDir.deleteRecursively()
        if (!targetDir.exists()) targetDir.mkdirs()
        unzip(tempFile, targetDir)
        saveHistory(StickerRecord(targetName, sourceUrl, System.currentTimeMillis()))

        // 新下载的，强制放第一位
        val newOrder = orderedPackageNames.toMutableList()
        if (!newOrder.contains(targetName)) {
            newOrder.add(0, targetName)
            updateOrder(newOrder)
        } else {
            newOrder.remove(targetName)
            newOrder.add(0, targetName)
            updateOrder(newOrder)
        }

        refreshStickers(keepTab = false)
        selectedPackageName = targetName
        Toast.makeText(context, "✅ 导入成功", Toast.LENGTH_SHORT).show()
    }

    fun handleDownload(url: String) {
        if (url.isEmpty()) return
        keyboardController?.hide()
        Toast.makeText(context, "下载中...", Toast.LENGTH_SHORT).show()
        downloadZip(url, context) { tempFile ->
            val rawName = try { java.net.URLDecoder.decode(url.substringAfterLast("/"), "UTF-8").substringBeforeLast(".") } catch(e:Exception){"Pack_${System.currentTimeMillis()}"}
            val targetDir = File(stickersRootDir, rawName)
            if (targetDir.exists()) {
                duplicateRenameText = "${rawName}_copy"
                showDuplicateDialog = tempFile to rawName
            } else {
                processDownload(tempFile, rawName, url)
                urlText = ""
            }
        }
    }

    // 新建文件夹
    fun createNewFolder(name: String) {
        val targetDir = File(stickersRootDir, name)
        if (targetDir.exists()) {
            Toast.makeText(context, "文件夹已存在", Toast.LENGTH_SHORT).show()
        } else {
            targetDir.mkdirs()
            val newOrder = orderedPackageNames.toMutableList()
            newOrder.add(0, name)
            updateOrder(newOrder)
            refreshStickers(keepTab = false)
            selectedPackageName = name
            Toast.makeText(context, "✅ 创建成功", Toast.LENGTH_SHORT).show()
        }
    }

    val importLocalZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val tempFile = File(context.cacheDir, "local.zip")
                val fos = FileOutputStream(tempFile)
                inputStream?.copyTo(fos)
                inputStream?.close(); fos.close()
                var name = "Local"
                it.path?.let { p -> name = p.substringAfterLast("/").substringBeforeLast(".") }
                val targetDir = File(stickersRootDir, name)
                if (targetDir.exists()) {
                    duplicateRenameText = "${name}_copy"
                    showDuplicateDialog = tempFile to name
                } else {
                    processDownload(tempFile, name, "")
                }
            } catch (e: Exception) { Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show() }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val currentPkg = selectedPackageName
            if (currentPkg != null) {
                context.contentResolver.openInputStream(it)?.use { input ->
                    File(stickersRootDir, currentPkg).let { dir ->
                        FileOutputStream(File(dir, "add_${System.currentTimeMillis()}.jpg")).use { out -> input.copyTo(out) }
                    }
                }
                // === 关键：添加图片后，keepTab = true，且不触发排序更新 ===
                refreshStickers(keepTab = true)
                Toast.makeText(context, "✅ 已添加", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "请先选择一个表情包", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getShareableApps(): List<AppInfo> { val i = Intent(Intent.ACTION_SEND).apply { type = "image/*" }; val pm = context.packageManager; return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct().mapNotNull { pkg -> try { val appInfo = pm.getApplicationInfo(pkg, 0); AppInfo(pkg, pm.getApplicationLabel(appInfo).toString(), pm.getApplicationIcon(appInfo)) } catch (e: Exception) { null } } }
    fun updatePinnedApps(newSet: Set<String>) { pinnedPackages = newSet; prefs.edit().putStringSet("pinned_apps", newSet).apply() }
    fun shareToApp(imageFile: File, packageName: String?) { try { val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile); val intent = Intent(Intent.ACTION_SEND).apply { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); if (packageName != null) setPackage(packageName) }; context.startActivity(intent) } catch (e: Exception) { if (packageName != null) { val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile); context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="image/*"; putExtra(Intent.EXTRA_STREAM, uri) }, "分享")) } else { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() } } }
    fun exportBackup() { if (isProcessing) return; isProcessing = true; scope.launch(Dispatchers.IO) { try { val json = gson.toJson(downloadHistory); val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); val f = File(dir, "meme_backup_${System.currentTimeMillis()}.json"); FileWriter(f).use { it.write(json) }; withContext(Dispatchers.Main) { Toast.makeText(context, "✅ 备份成功", Toast.LENGTH_LONG).show(); isProcessing = false } } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(context, "备份失败", Toast.LENGTH_SHORT).show(); isProcessing = false } } } }
    val restoreBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { try { val input = context.contentResolver.openInputStream(it); val json = input?.bufferedReader().use { br -> br?.readText() }; if (json != null) { val type = object : TypeToken<List<StickerRecord>>() {}.type; val records: List<StickerRecord> = gson.fromJson(json, type); val newHistory = downloadHistory.toMutableList(); records.forEach { record -> newHistory.removeAll { h -> h.folderName == record.folderName }; newHistory.add(0, record) }; downloadHistory = newHistory; prefs.edit().putString("download_history", gson.toJson(newHistory)).apply(); Toast.makeText(context, "恢复中...", Toast.LENGTH_SHORT).show(); records.forEach { record -> if (record.url.isNotEmpty()) downloadZip(record.url, context) { f -> processDownload(f, record.folderName, record.url, true) } } } } catch (e: Exception) { Toast.makeText(context, "失败", Toast.LENGTH_SHORT).show() } } }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it && enableNotification) showNotification(context) }

    LaunchedEffect(key1 = Unit) {
        refreshStickers()
        if (enableNotification) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else { showNotification(context) }
        }
        if (prefs.getBoolean("enable_floating", false) && Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, FloatingService::class.java))
            isFloatingEnabled = true
        }
    }

    // === UI ===
    if (currentScreen == "home") {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(shadowElevation = 4.dp, color = Color.White) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("表情包管理器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.Black)
                        Row {
                            IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, "设置") }
                            IconButton(onClick = { currentScreen = "manage" }) { Icon(Icons.AutoMirrored.Filled.List, "管理") }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = urlText, onValueChange = { urlText = it }, label = { Text("ZIP 链接", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color.LightGray), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { handleDownload(urlText) }))
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { importLocalZipLauncher.launch("application/zip") }, modifier = Modifier.padding(horizontal = 4.dp)) { Text("➕ 导入", fontSize = 12.sp) }
                        Button(onClick = { handleDownload(urlText) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Icon(Icons.Default.AddCircle, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(4.dp)); Text("下载") }
                    }
                }
            }

            if (stickerPackages.isNotEmpty() && orderedPackageNames.isNotEmpty()) {
                val validTabs = orderedPackageNames.filter { stickerPackages.containsKey(it) }
                if (validTabs.isNotEmpty()) {
                    val selectedIndex = validTabs.indexOf(selectedPackageName).coerceAtLeast(0)
                    ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 16.dp, containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary, indicator = { p -> SecondaryIndicator(Modifier.tabIndicatorOffset(p[selectedIndex]), height = 3.dp, color = MaterialTheme.colorScheme.primary) }, divider = { HorizontalDivider(color = Color(0xFFEEEEEE)) }) {
                        validTabs.forEachIndexed { i, name -> Tab(selected = selectedIndex == i, onClick = { selectedPackageName = name }, text = { Text(if(name.length>6) name.take(5)+".." else name, fontWeight = if(selectedIndex == i) FontWeight.Bold else FontWeight.Normal) }) }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        val currentName = validTabs.getOrNull(selectedIndex)
                        val currentImages = stickerPackages[currentName] ?: emptyList()
                        if (currentImages.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无图片，点击右下角添加", color = Color.Gray) }
                        } else {
                            LazyVerticalGrid(columns = GridCells.Adaptive(90.dp), contentPadding = PaddingValues(bottom = 80.dp, start = 12.dp, end = 12.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                items(currentImages) { img ->
                                    Card(shape = RoundedCornerShape(8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                        Box(modifier = Modifier.aspectRatio(1f).fillMaxWidth().combinedClickable(onClick = { showShareSheet = img }, onLongClick = { showDeleteImageDialog = img })) {
                                            Image(painter = rememberAsyncImagePainter(img), contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                                        }
                                    }
                                }
                            }
                        }
                        FloatingActionButton(onClick = { imagePickerLauncher.launch("image/*") }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, shape = CircleShape) { Icon(Icons.Default.Add, "添加") }
                    }
                } else { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无有效表情包", color = Color.Gray) } }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📦", fontSize = 48.sp); Spacer(modifier = Modifier.height(16.dp)); Text("空空如也", color = Color.Gray)
                        Button(onClick = { currentScreen = "manage" }, modifier = Modifier.padding(top=16.dp)) { Text("去管理页新建") }
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { currentScreen = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    Text("管理 (${orderedPackageNames.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { showCreateFolderDialog = true }) { Icon(Icons.Default.AddCircle, "新建", tint = MaterialTheme.colorScheme.primary) }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(orderedPackageNames) { folderName ->
                    if (File(stickersRootDir, folderName).exists()) {
                        val isPinned = pinnedFolders.contains(folderName)
                        ListItem(
                            headlineContent = { Text(folderName, fontWeight = FontWeight.Medium) },
                            leadingContent = { Icon(Icons.Default.AddCircle, null, tint = if (isPinned) MaterialTheme.colorScheme.primary else Color.LightGray) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { moveFolderUp(folderName) }) { Text("上移") }
                                    TextButton(onClick = { togglePinFolder(folderName) }) { Text(if (isPinned) "取置顶" else "置顶", color = if(isPinned) Color.Red else Color.Gray) }
                                    IconButton(onClick = { File(stickersRootDir, folderName).deleteRecursively(); val newOrder = orderedPackageNames.toMutableList(); newOrder.remove(folderName); updateOrder(newOrder); refreshStickers(keepTab = false) }) { Icon(Icons.Default.Delete, "删除", tint = Color.Gray) }
                                }
                            }
                        )
                        HorizontalDivider(color = Color(0xFFF5F5F5))
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) { AlertDialog(onDismissRequest = { showCreateFolderDialog = false }, title = { Text("新建") }, text = { OutlinedTextField(value = newFolderNameText, onValueChange = { newFolderNameText = it }, label = { Text("名称") }, singleLine = true) }, confirmButton = { Button(onClick = { if (newFolderNameText.isNotBlank() && !newFolderNameText.contains("/")) { createNewFolder(newFolderNameText); newFolderNameText = ""; showCreateFolderDialog = false } else { Toast.makeText(context, "无效", Toast.LENGTH_SHORT).show() } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("取消") } }) }
    if (showSettingsDialog) { AlertDialog(onDismissRequest = { showSettingsDialog = false }, title = { Text("设置") }, text = { Column { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("通知栏"); Switch(checked = enableNotification, onCheckedChange = { toggleNotification(it) }) }; Spacer(modifier = Modifier.height(16.dp)); HorizontalDivider(); Spacer(modifier = Modifier.height(16.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("悬浮窗"); Switch(checked = isFloatingEnabled, onCheckedChange = { toggleFloatingService(it) }) }; Spacer(modifier = Modifier.height(24.dp)); Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { OutlinedButton(onClick = { restoreBackupLauncher.launch("application/json"); showSettingsDialog = false }) { Text("导入恢复") }; Button(onClick = { exportBackup(); showSettingsDialog = false }, enabled = !isProcessing) { Text("导出备份") } } } }, confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("关闭") } }) }
    if (showDuplicateDialog != null) { val (t, n) = showDuplicateDialog!!; AlertDialog(onDismissRequest = { showDuplicateDialog = null }, title = { Text("名称冲突") }, text = { Column { Text("文件夹 \"$n\" 已存在。"); Spacer(modifier = Modifier.height(8.dp)); OutlinedTextField(value = duplicateRenameText, onValueChange = { duplicateRenameText = it }, label = { Text("新名称") }, singleLine = true) } }, confirmButton = { Button(onClick = { if (duplicateRenameText.isNotBlank() && !duplicateRenameText.contains("/")) { processDownload(t, duplicateRenameText, "", false); showDuplicateDialog = null } }) { Text("保存副本") } }, dismissButton = { TextButton(onClick = { processDownload(t, n, "", true); showDuplicateDialog = null }) { Text("覆盖旧的") } }) }
    if (showShareSheet != null) { ModalBottomSheet(onDismissRequest = { showShareSheet = null }, containerColor = Color.White) { Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text("发送...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp)); LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(pinnedPackages.toList()) { pkg -> val pm = context.packageManager; val info = try { pm.getApplicationInfo(pkg, 0) } catch (e:Exception) { null }; if (info != null) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { shareToApp(showShareSheet!!, pkg); showShareSheet = null }) { Image(painter = rememberAsyncImagePainter(pm.getApplicationIcon(info)), contentDescription = null, modifier = Modifier.size(50.dp)); Spacer(modifier = Modifier.height(4.dp)); Text(pm.getApplicationLabel(info).toString().take(4), fontSize = 12.sp, maxLines = 1) } } }; item { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { showAddAppDialog = true }) { Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null, tint = Color.Gray) }; Text("管理", fontSize = 12.sp) } }; item { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { shareToApp(showShareSheet!!, null); showShareSheet = null }) { Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Share, null, tint = Color.Gray) }; Text("更多", fontSize = 12.sp) } } }; Spacer(modifier = Modifier.height(40.dp)) } } }
    if (showAddAppDialog) { val allApps = remember { getShareableApps() }; AlertDialog(onDismissRequest = { showAddAppDialog = false }, title = { Text("常用") }, text = { LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(300.dp)) { items(allApps) { app -> val isSelected = pinnedPackages.contains(app.packageName); Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(8.dp)).background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { val newSet = pinnedPackages.toMutableSet(); if (isSelected) newSet.remove(app.packageName) else newSet.add(app.packageName); updatePinnedApps(newSet) }.padding(8.dp)) { Image(painter = rememberAsyncImagePainter(app.icon), contentDescription = null, modifier = Modifier.size(32.dp)); Text(app.label, fontSize = 10.sp, maxLines = 1) } } } }, confirmButton = { TextButton(onClick = { showAddAppDialog = false }) { Text("完成") } }) }
    if (showDeletePackageDialog) { AlertDialog(onDismissRequest = { showDeletePackageDialog = false }, title = { Text("删除表情包") }, text = { Text("确定删除整个包？") }, confirmButton = { TextButton(onClick = { val pkg = stickerPackages.keys.toList().find { it == selectedPackageName }; if (pkg != null) { File(stickersRootDir, pkg).deleteRecursively(); val newOrder = orderedPackageNames.toMutableList(); newOrder.remove(pkg); updateOrder(newOrder); refreshStickers(keepTab = false); showDeletePackageDialog = false } }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("删除") } }, dismissButton = { TextButton(onClick = { showDeletePackageDialog = false }) { Text("取消") } }) }
    if (showDeleteImageDialog != null) { AlertDialog(onDismissRequest = { showDeleteImageDialog = null }, title = { Text("删除") }, text = { Text("确定删除？") }, confirmButton = { TextButton(onClick = { showDeleteImageDialog?.delete(); refreshStickers(keepTab = true); showDeleteImageDialog = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)) { Text("删除") } }, dismissButton = { TextButton(onClick = { showDeleteImageDialog = null }) { Text("取消") } }) }
}

// 底部函数保持不变...
fun showNotification(context: Context) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel("meme_shortcut", "快捷启动", NotificationManager.IMPORTANCE_LOW)); nm.notify(1001, android.app.Notification.Builder(context, "meme_shortcut").setSmallIcon(android.R.drawable.ic_menu_gallery).setContentTitle("表情包").setContentText("点击斗图").setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }, PendingIntent.FLAG_IMMUTABLE)).setOngoing(true).build()) } }
fun cancelNotification(context: Context) { val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.cancel(1001) }
fun downloadZip(url: String, context: android.content.Context, onDownloadSuccess: (File) -> Unit) { val client = okhttp3.OkHttpClient(); Thread { try { val response = client.newCall(okhttp3.Request.Builder().url(url).build()).execute(); if (response.isSuccessful) { val destFile = File(context.cacheDir, "temp_sticker.zip"); FileOutputStream(destFile).use { it.write(response.body!!.bytes()) }; (context as android.app.Activity).runOnUiThread { onDownloadSuccess(destFile) } } else { (context as android.app.Activity).runOnUiThread { Toast.makeText(context, "下载失败", Toast.LENGTH_SHORT).show() } } } catch (e: Exception) { (context as android.app.Activity).runOnUiThread { Toast.makeText(context, "错误: ${e.message}", Toast.LENGTH_SHORT).show() } } }.start() }
fun unzip(zipFile: File, targetDir: File) { try { ZipInputStream(java.io.FileInputStream(zipFile)).use { zip -> var entry = zip.nextEntry; while (entry != null) { val newFile = File(targetDir, entry.name); if (entry.isDirectory) newFile.mkdirs() else { newFile.parentFile?.mkdirs(); FileOutputStream(newFile).use { out -> zip.copyTo(out) } }; entry = zip.nextEntry } } } catch (e: Exception) { e.printStackTrace() } }

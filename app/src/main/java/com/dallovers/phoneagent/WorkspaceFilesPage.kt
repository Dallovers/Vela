package com.dallovers.phoneagent

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorkspaceFilesPage(modifier: Modifier = Modifier, onClose: () -> Unit, onDirtyChanged: (Boolean) -> Unit, onBackHandler: ((() -> Unit)?) -> Unit, controller: AgentController, workspaceId: String, workspaceName: String, onChooseWorkspace: () -> Unit) {
    val context = LocalContext.current
    fun workspace() = controller.workspaceFiles(workspaceId)
    var directory by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var picking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var files by remember(directory) { mutableStateOf(emptyList<WorkspaceEntry>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var leaving by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf("") }
    fun leave() { if (text != saved) leaving = true else selected = null }
    var loading by remember(directory) { mutableStateOf(true) }
    var directoryError by remember(directory) { mutableStateOf("") }
    LaunchedEffect(workspaceId, directory, refresh) {
        loading = true; directoryError = ""
        runCatching { withContext(Dispatchers.IO) { workspace().entries(directory) } }
            .onSuccess { files = it }.onFailure { directoryError = it.message ?: "读取目录失败" }
        loading = false
    }
    val currentBack by rememberUpdatedState<() -> Unit>({
        if (!busy) {
            if (selected != null) leave() else if (directory.isNotEmpty()) {
                directory = directory.substringBeforeLast('/', ""); error = ""
            } else onClose()
        }
    })
    val stableBack = remember { { currentBack() } }
    SideEffect { onDirtyChanged(selected != null && text != saved); onBackHandler(stableBack) }
    DisposableEffect(Unit) { onDispose { onDirtyChanged(false); onBackHandler(null) } }
    BackHandler(selected != null || directory.isNotEmpty() || busy) { currentBack() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = ""
            runCatching { withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "导入-${System.currentTimeMillis()}.txt"
                WorkspaceNames.validate(name)
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= WorkspaceFiles.MAX_BYTES) { "仅支持 1 MB 以内的文本" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: kotlin.error("无法读取文件")
                workspace().save(childPath(directory, name), WorkspaceFiles.decode(bytes), create = true)
            } }.onSuccess { refresh++ }.onFailure { error = "导入失败：${it.message ?: "请选择 UTF-8 文本文件"}" }
            busy = false
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) } ?: kotlin.error("无法写入文件")
            } }.onSuccess { error = "已导出" }.onFailure { error = "导出失败，请换个保存位置" }
            busy = false; exportText = ""
        }
    }
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(workspaceName, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onChooseWorkspace, enabled = !busy) { Text("切换工作区") }
            }
            Row {
                TextButton({ picking = true }, enabled = !busy) { Text("打开手机文件夹") }
                TextButton({
                    runCatching { controller.startFileChat(workspaceId, directory) }.onSuccess { onClose() }
                        .onFailure { error = it.message ?: "无法创建对话" }
                }, enabled = !busy && !loading && directoryError.isEmpty()) { Text("询问此文件夹") }
            }
            Text(if (directory.isEmpty()) "工作区根目录" else "/$directory", style = MaterialTheme.typography.bodyMedium)
            if (directory.isNotEmpty()) TextButton({ currentBack() }, enabled = !busy) { Text("上一级") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { newName = ""; create = true }, enabled = !busy) { Text("新建文件") }
                OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/json")) }, enabled = !busy) { Text("导入文本") }
            }
            if (directoryError.isNotEmpty()) {
                Text(directoryError, color = MaterialTheme.colorScheme.error)
                TextButton({ refresh++ }) { Text("重新读取") }
            } else if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (files.isEmpty()) Text("此目录为空")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(files, key = { it.name }) { file ->
                    val path = childPath(directory, file.name)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            error = ""
                            if (file.directory) directory = path
                            else scope.launch {
                                busy = true
                                runCatching { withContext(Dispatchers.IO) { workspace().read(path) } }.onSuccess {
                                    selected = path; text = it; saved = it
                                }.onFailure { error = "无法用文本编辑器读取。可点文件旁的“打开方式”。" }
                                busy = false
                            }
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(file.name)
                                Text(if (file.directory) "文件夹" else "${file.size} 字节", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (!file.directory) TextButton({ scope.launch {
                            busy = true
                            runCatching { controller.referenceFile(workspaceId, path) }.onSuccess { onClose() }
                                .onFailure { error = it.message ?: "无法引用文件" }
                            busy = false
                        } }, enabled = !busy) { Text("询问") }
                        if (!file.directory) TextButton({
                            runCatching { WorkspaceViewerProvider.open(context, workspaceId, path) }
                                .onFailure { error = "没有可用的文件查看器" }
                        }, enabled = !busy) { Text("打开方式") }
                    }
                }
            }
        } else {
            Text(selected.orEmpty(), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { leave() }, enabled = !busy) { Text("返回列表") }
                Button(onClick = { scope.launch {
                    busy = true
                    runCatching { withContext(Dispatchers.IO) { workspace().save(selected!!, text, expected = saved) } }
                        .onSuccess { saved = text; refresh++; error = "已保存" }.onFailure { error = it.message ?: "保存失败" }
                    busy = false
                } }, enabled = !busy && text != saved) { Text("保存") }
                OutlinedButton(onClick = { exportText = text; exporter.launch(selected!!.substringAfterLast('/')) }, enabled = !busy) { Text("导出") }
            }
            TextButton(onClick = { scope.launch {
                busy = true
                runCatching { controller.referenceFile(workspaceId, selected!!) }.onSuccess { onClose() }.onFailure { error = it.message ?: "无法引用文件" }
                busy = false
            } }, enabled = !busy && text == saved) { Text("引用到新对话") }
            OutlinedTextField(text, { text = it }, enabled = !busy, label = { Text(if (text == saved) "内容" else "内容 · 未保存") }, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall)
        if (text != saved && selected != null) Text("保存后可切换其他页面。", style = MaterialTheme.typography.bodySmall)
    }
    if (picking) PhoneFolderPicker(controller, { picking = false }, { picking = false; directory = ""; refresh++ })
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建文件") },
        text = { OutlinedTextField(newName, { newName = it.take(100) }, label = { Text("文件名") }, placeholder = { Text("笔记.md") }) },
        confirmButton = { TextButton(onClick = {
            create = false
            scope.launch {
                busy = true
                runCatching { withContext(Dispatchers.IO) {
                    WorkspaceNames.validate(newName)
                    childPath(directory, newName).also { workspace().save(it, "", create = true) }
                } }.onSuccess { refresh++; selected = it; text = ""; saved = ""; error = "" }
                    .onFailure { error = it.message ?: "创建失败" }
                busy = false
            }
        }, enabled = newName.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("取消") } })
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("还有修改没保存") }, text = { Text("返回后，这次未保存的修改会丢失。") },
        confirmButton = { TextButton(onClick = { selected = null; leaving = false }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("继续编辑") } })
}

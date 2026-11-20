package com.dallovers.phoneagent

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhoneFolderPicker(controller: AgentController, onDismiss: () -> Unit, onChosen: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { PhoneStorage(context) }
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var volume by rememberSaveable { mutableStateOf<String?>(null) }
    var path by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        granted = Environment.isExternalStorageManager()
        if (!granted) error = "尚未获得所有文件访问权限"
    }
    LaunchedEffect(granted, volume, path) {
        entries = emptyList(); ready = false
        if (granted) {
            error = ""
            val id = volume
            runCatching { withContext(Dispatchers.IO) {
                if (id == null) storage.volumes().map { it.id to it.name }
                else {
                    val root = storage.root(PhoneFolder(id, path))
                    WorkspaceFiles(root, path).entries("").filter { it.directory }.map { it.name to it.name }
                }
            } }.onSuccess { entries = it; ready = true }.onFailure { error = it.message ?: "无法读取目录" }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("打开手机文件夹") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!granted) Text("在系统设置中允许 Vela 访问共享存储，随后选择要用作工作区的文件夹。保存修改会更新原文件。")
            else {
                Text(if (volume == null) "选择存储设备" else "/$path", style = MaterialTheme.typography.bodySmall)
                if (volume != null) TextButton(onClick = {
                    ready = false; entries = emptyList()
                    if (path.isEmpty()) volume = null else path = path.substringBeforeLast('/', "")
                }) { Text("上一级") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(entries, key = { it.first }) { (id, name) ->
                        TextButton(onClick = {
                            ready = false; entries = emptyList()
                            if (volume == null) volume = id else path = childPath(path, id)
                        }, modifier = Modifier.fillMaxWidth()) { Text(name) }
                    }
                }
                if (ready && entries.isEmpty()) Text(if (volume == null) "没有可用的存储设备" else "没有子文件夹")
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        if (!granted) Button(onClick = {
            runCatching {
                settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName)))
            }.onFailure {
                runCatching { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    .onFailure { error = "请在系统设置中搜索“所有文件访问权限”，允许 Vela 后重新打开本页" }
            }
        }) { Text("前往系统设置") }
        else Button(enabled = ready && volume != null, onClick = {
            runCatching { controller.openPhoneFolder(PhoneFolder(checkNotNull(volume), path)) }
                .onSuccess { onChosen() }.onFailure { error = it.message ?: "打开失败" }
        }) { Text("使用此文件夹") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

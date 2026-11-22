package com.dallovers.phoneagent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspacePickerSheet(controller: AgentController, state: AgentState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var removing by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var picking by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (picking) {
        PhoneFolderPicker(controller, { picking = false }, { picking = false; onDismiss() })
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("工作区", style = MaterialTheme.typography.titleLarge)
            Text("新对话与文件页使用所选工作区，已有对话保持原来的工作区。", style = MaterialTheme.typography.bodyMedium)
            Button({ picking = true }, modifier = Modifier.fillMaxWidth()) { Text("打开手机文件夹") }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.workspaces, key = { it.id }) { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { controller.chooseWorkspace(item.id) }.onSuccess { onDismiss() }.onFailure { error = it.message ?: "切换失败" }
                        }, modifier = Modifier.weight(1f)) { Text((if (item.id == state.activeWorkspaceId) "当前 · " else "") + (item.phone?.let { "${item.name} · ${it.volume}/${it.path}" } ?: item.name)) }
                        // The default workspace is built in, so it has nothing to rename or remove.
                        if (item.id != WorkspacePaths.DEFAULT) Box {
                            IconButton(onClick = { menuFor = item.id }, modifier = Modifier.semantics { contentDescription = "${item.name} 的操作" }) { AgentGlyph("more") }
                            DropdownMenu(expanded = menuFor == item.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("改名") }, onClick = { menuFor = null; renaming = item })
                                DropdownMenuItem(text = { Text("移除") }, onClick = { menuFor = null; removing = item })
                            }
                        }
                    }
                }
            }
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("新工作区名称") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                controller.createWorkspace(name).onSuccess { onDismiss() }.onFailure { error = it.message ?: "创建失败" }
            }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("创建工作区") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
        }
    }
    renaming?.let { target ->
        var value by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            title = { Text("工作区改名") },
            text = { OutlinedTextField(value, { value = it.take(40) }, label = { Text("名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                error = ""
                controller.renameWorkspace(target.id, value)
                    .onSuccess { renaming = null; notice = "已改名" }
                    .onFailure { error = it.message ?: "改名失败" }
            }, enabled = value.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton({ renaming = null }) { Text("取消") } })
    }
    removing?.let { target ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("移除工作区「${target.name}」？") },
            text = {
                Text(if (target.phone != null) "只从列表里移除，手机文件夹里的文件不会被删。"
                    else "只从列表里移除，工作区里的文件不会被删，之后会显示它们还在哪里。")
            },
            confirmButton = { TextButton(onClick = {
                error = ""
                controller.deleteWorkspace(target.id)
                    .onSuccess { kept ->
                        removing = null
                        notice = if (kept != null) "已移除，文件仍在 ${kept.absolutePath}" else "已移除"
                    }
                    .onFailure { removing = null; error = it.message ?: "移除失败" }
            }) { Text("移除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPickerSheet(controller: AgentController, workspaceId: String, model: String,
                                   initial: List<TextAttachment>, onDismiss: () -> Unit, onDone: (List<TextAttachment>) -> Unit) {
    var directory by remember { mutableStateOf("") }
    var files by remember(directory) { mutableStateOf(emptyList<WorkspaceEntry>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(workspaceId, directory) {
        busy = true; error = ""
        runCatching { withContext(Dispatchers.IO) { controller.workspaceFiles(workspaceId).entries(directory) } }
            .onSuccess { files = it }.onFailure { error = it.message ?: "读取文件列表失败" }
        busy = false
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("引用文件", style = MaterialTheme.typography.titleLarge)
            Text("所选资料会随消息发给 ${model.ifBlank { "当前模型" }}。最多 4 个文件，单文件最多 50 MB，文档按需分段读取。", style = MaterialTheme.typography.bodyMedium)
            if (directory.isNotEmpty()) TextButton({ directory = directory.substringBeforeLast('/', "") }, enabled = !busy) { Text("上一级 · /$directory") }
            if (files.isEmpty() && !busy && error.isBlank()) Text("此目录为空")
            LazyColumn(Modifier.weight(1f)) {
                items(files, key = { it.name }) { file ->
                    val name = childPath(directory, file.name)
                    val bytes = file.size
                    if (file.directory) {
                        TextButton({ directory = name }, enabled = !busy) { Text("文件夹 · ${file.name}") }
                        return@items
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = name in selected || initial.any { it.name == name }, enabled = !busy && initial.none { it.name == name } && bytes <= WorkspaceFiles.MAX_READ_BYTES && (selected.size + initial.size < 4 || name in selected),
                            onCheckedChange = { selected = if (it) selected + name else selected - name })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(file.name); Text(if (bytes > WorkspaceFiles.MAX_READ_BYTES) "文件超过50 MB" else "$bytes 字节", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = { scope.launch {
                busy = true; error = ""
                runCatching { (initial + controller.readAttachments(workspaceId, selected.toList())).also { TextAttachment.validateBatch(it, workspaceId) } }
                    .onSuccess { onDone(it) }.onFailure { error = it.message ?: "附件无法读取，请重新选择" }
                busy = false
            } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("使用所选文件（${selected.size}）") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun AttachmentPreview(file: TextAttachment, store: DeviceAttachments, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(file.name) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (file.document.isNotEmpty()) Text(if (file.pageCount > 0) "PDF · ${file.pageCount} 页。已保留完整文件，可直接要求按页讲解、概括或回答问题。" else "已保留完整文件，模型将按问题分段解析。")
            else if (file.pages.isEmpty()) SelectionContainer { Text(file.text) }
            file.pages.forEachIndexed { index, digest -> SnapshotImage(store, digest, "第 ${index + 1} 页", false) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
internal fun SaveReplyFileDialog(controller: AgentController, session: TaskSession, message: ConversationMessage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val parts = remember(message.id) { ReplyFiles.parts(message.text) }
    var part by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("回复-${message.id.take(6)}.md") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("保存为文件") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存到工作区 /${session.fileChatDirectory.orEmpty()}，已有同名文件时会提示换名。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                parts.forEachIndexed { index, item -> FilterChip(selected = part == index, onClick = {
                    part = index; name = "回复-${message.id.take(6)}${if (index == 0) "" else "-$index"}.${item.extension}"
                }, enabled = !busy, label = { Text(item.label) }) }
            }
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("文件名") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text(parts[part].text.take(3000), maxLines = 7, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy && name.isNotBlank(), onClick = { scope.launch {
        busy = true
        runCatching { controller.saveReplyFile(session.id, message.id, part, name) }.onSuccess { Toast.makeText(context, "文件已保存，可在对话详情的“文件”中查看", Toast.LENGTH_SHORT).show(); onDismiss() }
            .onFailure { error = it.message ?: "文件未能保存，请检查名称" }
        busy = false
    } }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

package com.dallovers.phoneagent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.SessionWorkspace(
    controller: AgentController, state: AgentState, settings: ModelSettings,
    onModel: () -> Unit, onPhone: () -> Unit, detail: Int, onDetail: (Int) -> Unit, onWorkspace: () -> Unit, onFiles: (String) -> Unit,
) {
    val session = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    val question = session?.question
    var draft by rememberSaveable(session?.id, question?.id) { mutableStateOf(controller.loadDraft(session?.id, question?.id)) }
    var editing by remember(session?.id) { mutableStateOf<SessionTask?>(null) }
    var deleting by remember(session?.id) { mutableStateOf(false) }
    var extras by remember { mutableStateOf(false) }
    var composing by remember(session?.id) { mutableStateOf(false) }
    var replaceCurrent by remember(session?.id) { mutableStateOf(true) }
    val mode = session?.mode ?: ConversationMode.PHONE
    val workspaceId = session?.workspaceId ?: state.activeWorkspaceId
    val workspaceName = state.workspaces.firstOrNull { it.id == workspaceId }?.name ?: "工作区"
    if (session?.fileChatDirectory != null) {
        Text("文件对话 · $workspaceName/${session.fileChatDirectory}　模型可按需读取此目录的文件名和文本",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
    var attachments by remember(session?.id, workspaceId) { mutableStateOf(controller.loadAttachmentDraft(session?.id, workspaceId)) }
    var addSheet by remember(session?.id, workspaceId) { mutableStateOf(false) }
    var importing by remember(session?.id, workspaceId) { mutableStateOf(false) }
    val visibleFiles = if (question == null) attachments else emptyList()
    var useScreen by rememberSaveable(session?.id, workspaceId, visibleFiles.isEmpty()) { mutableStateOf(false) }
    var attachmentPicker by remember(session?.id, workspaceId) { mutableStateOf(false) }
    var previewFile by remember(session?.id) { mutableStateOf<TextAttachment?>(null) }
    var saveReply by remember(session?.id) { mutableStateOf<ConversationMessage?>(null) }
    var exportText by remember { mutableStateOf("") }
    var organizing by remember(session?.id) { mutableStateOf(false) }
    val speech = rememberReplySpeech(session?.id)
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val owner = "${session?.id ?: "new"}:$workspaceId:${question?.id.orEmpty()}"
    val voice = rememberVoiceInput(owner, onText = {
        draft = listOf(draft.trimEnd(), it).filter { part -> part.isNotBlank() }.joinToString(" ")
        controller.saveDraft(session?.id, question?.id, draft)
    }, onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, onEvent = controller::voiceEvent)
    val listening = voice.recorder.active
    key(session?.id, workspaceId) {
        ComposerPicker(controller, workspaceId, addSheet, { addSheet = false }, attachments, {
            controller.saveAttachmentDraft(session?.id, workspaceId, it); attachments = it
        }, { attachmentPicker = true }, { extras = true }, { voice.start() }, { importing = it },
            { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, question == null && session?.archived != true)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) } ?: error("无法写入") }.isSuccess
            Toast.makeText(context, if (ok) "对话已导出" else "导出失败，请换一个保存位置", Toast.LENGTH_SHORT).show()
        }
        exportText = ""
    }
    if (attachmentPicker) AttachmentPickerSheet(controller, workspaceId, settings.model, attachments, onDismiss = { attachmentPicker = false }, onDone = {
        attachments = it; controller.saveAttachmentDraft(session?.id, workspaceId, it); attachmentPicker = false
    })
    previewFile?.let { AttachmentPreview(it, controller.media, onDismiss = { previewFile = null }) }
    if (session != null) saveReply?.let { SaveReplyFileDialog(controller, session, it, onDismiss = { saveReply = null }) }
    val list = rememberLazyListState()
    val messages = session?.conversationMessages().orEmpty()
    LaunchedEffect(session?.id, messages.size, question?.id, state.streamingSessionId) {
        if (messages.isNotEmpty()) list.animateScrollToItem(if (question != null || state.streamingSessionId == session?.id) messages.size else messages.lastIndex)
    }
    if (session == null) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(28.dp), horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val greeting = when (LocalTime.now().hour) { in 5..10 -> "早上好"; in 11..13 -> "中午好"; in 14..17 -> "下午好"; else -> "晚上好" }
                Text(greeting, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("想做什么，直接说。", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                if (mode == ConversationMode.CHAT) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Suggestion("帮我写写", "把想法整理成文字", "帮我写一段文字，先问我写给谁、想表达什么。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                        Suggestion("一起想想", "理清思路与选择", "我有件事想和你商量，先听我说。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Suggestion("学点东西", "把复杂问题讲明白", "我想学点东西，先问问我对什么感兴趣。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                        Suggestion("安排一下", "一起规划接下来的事", "帮我安排接下来的事情，先问我有哪些事和时间限制。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Suggestion("看看屏幕", "说说当前页面", "观察当前页面，用中文告诉我上面有什么，先不要点击。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                        Suggestion("试一下", "在本地练习中体验", "phone", Modifier.weight(1f)) { onPhone() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Suggestion("帮我找找", "查找页面里的内容", "看看当前页面有什么可以搜索的内容，先问我想找什么。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                        Suggestion("分几步做", "复杂的事一步步来", "先观察当前页面，问问我想完成什么，确认后再操作。", Modifier.weight(1f)) { draft = it; controller.saveDraft(null, null, it) }
                    }
                }
                Text("进度会自动保存。拿不准时，助手会在当前页面问你。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(messages, key = { it.id }) { message ->
                val user = message.role == "user"
                val task = session.tasks.firstOrNull { it.id == message.taskId }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (user) Surface(shape = RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.widthIn(max = 560.dp)) { Text(message.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                    else {
                        MessageBody(message.text)
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("助手回复", message.text))
                            }) { Text("复制", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = { runCatching {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message.text), "分享回复"))
                            } }) { Text("分享", style = MaterialTheme.typography.labelMedium) }
                            TextButton(onClick = { speech.toggle(message.id, message.text) }) {
                                Text(if (speech.speakingId == message.id) "停止朗读" else "朗读", style = MaterialTheme.typography.labelMedium)
                            }
                            run {
                                var messageMenu by remember(message.id) { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { messageMenu = true }) { Text("更多", style = MaterialTheme.typography.labelMedium) }
                                    DropdownMenu(expanded = messageMenu, onDismissRequest = { messageMenu = false }) {
                                        DropdownMenuItem(text = { Text("从这里继续") }, enabled = !state.running && mode == ConversationMode.CHAT, onClick = {
                                            messageMenu = false; speech.stop(); controller.branchReply(session.id, message.id, false, settings)
                                        })
                                        DropdownMenuItem(text = { Text("保存为文件") }, onClick = { messageMenu = false; saveReply = message })
                                        DropdownMenuItem(text = { Text("重新回答") }, enabled = !state.running && mode == ConversationMode.CHAT, onClick = {
                                            messageMenu = false; speech.stop(); controller.branchReply(session.id, message.id, true, settings)
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (user && !task?.attachments.isNullOrEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        task?.attachments.orEmpty().forEach { file -> TextButton(onClick = { previewFile = file }) { Text(file.name) } }
                    }
                    if (user && task?.status == TaskStatus.TODO && session.status == SessionStatus.RUNNING)
                        Text("排队中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.streamingSessionId == session.id) item(key = "stream") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.streamingText.ifBlank { "正在思考…" }, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            question?.let { pending -> item(key = pending.id) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("等你回答", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(pending.prompt, style = MaterialTheme.typography.titleMedium)
                        Text(pending.reason, style = MaterialTheme.typography.bodyMedium)
                        pending.options.forEach { option -> OutlinedButton(onClick = {
                            focus.clearFocus(); controller.answerQuestion(session.id, pending.id, option)
                        }, modifier = Modifier.fillMaxWidth()) { Text(option) } }
                        Text("也可以在下面直接回复。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { controller.dismissQuestion(session.id, pending.id, "暂停") }) { Text("稍后再说") }
                            TextButton(onClick = { controller.dismissQuestion(session.id, pending.id, "跳过") }) { Text("跳过") }
                        }
                    }
                }
            } }
            if (session.notice.isNotBlank()) item(key = "notice") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (mode == ConversationMode.PHONE && !state.connected) TextButton(onClick = onPhone) { Text("打开手机操控") }
                    else if (settings.endpoint.isBlank() || settings.model.isBlank() || settings.apiKey.isBlank()) TextButton(onClick = onModel) { Text("设置模型") }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session?.archived == true) Text("这个对话已归档。移出归档后可以继续聊天。", style = MaterialTheme.typography.bodySmall)

        if (session != null && session.tasks.isNotEmpty() && (mode == ConversationMode.PHONE || session.status in setOf(SessionStatus.PAUSED, SessionStatus.FAILED, SessionStatus.READY))) {
            Surface(onClick = { focus.clearFocus(); onDetail(0) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AgentGlyph(if (session.status == SessionStatus.COMPLETED) "check" else "phone")
                    Column(Modifier.weight(1f)) {
                        Text(session.status.label(), style = MaterialTheme.typography.labelLarge)
                        Text(if (mode == ConversationMode.CHAT) "${messages.size} 条消息" else "${session.tasks.count { it.status == TaskStatus.COMPLETED }} / ${session.tasks.size} 已完成", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (session.status == SessionStatus.RUNNING) TextButton(onClick = { controller.stop("已暂停") }) { Text("暂停") }
                    else if (!session.archived && question == null && session.tasks.any { it.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED) })
                        TextButton(onClick = { focus.clearFocus(); controller.runSession(session.id, settings) }, enabled = !state.running) { Text("继续") }
                    else Text("查看", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (visibleFiles.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        visibleFiles.forEach { file -> InputChip(selected = true, onClick = { previewFile = file },
                            leadingIcon = { file.pages.firstOrNull()?.let { SnapshotImage(controller.media, it, file.name, true) } ?: AgentGlyph("file") },
                            label = { Text(file.name + if (file.pageCount > 0) " · ${file.pageCount}页" else "", Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = {
                                IconButton(onClick = { attachments = attachments - file; controller.saveAttachmentDraft(session?.id, workspaceId, attachments) },
                                    modifier = Modifier.semantics { contentDescription = "移除${file.name}" }) { AgentGlyph("close") }
                            }) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("随消息发给 ${settings.model.ifBlank { "当前模型" }}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        if (session == null) FilterChip(selected = useScreen, onClick = { useScreen = !useScreen }, label = { Text("结合手机操作") })
                    }
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        FilledTonalIconButton(onClick = {
                            if (listening) voice.recorder.cancel()
                            else if (question != null || session?.archived == true) extras = true
                            else { focus.clearFocus(); addSheet = true }
                        }, enabled = !importing, modifier = Modifier.semantics { contentDescription = if (listening) "取消听写" else if (question != null || session?.archived == true) "更多" else "添加图片或文件" }) {
                            AgentGlyph(if (listening) "close" else "plus")
                        }
                        DropdownMenu(expanded = extras, onDismissRequest = { extras = false }) {
                            DropdownMenuItem(text = { Text(workspaceName) }, onClick = { extras = false; if (session == null) onWorkspace() else onFiles(workspaceId) })
                            DropdownMenuItem(text = { Text("手机操控") }, onClick = { extras = false; onPhone() })
                            if (session != null) {
                                DropdownMenuItem(text = { Text(if (mode == ConversationMode.CHAT) "对话详情" else "查看进度") }, onClick = { extras = false; focus.clearFocus(); onDetail(0) })
                                DropdownMenuItem(text = { Text("记住的信息") }, onClick = { extras = false; focus.clearFocus(); onDetail(1) })
                                DropdownMenuItem(text = { Text("命名与整理") }, onClick = { extras = false; organizing = true })
                                DropdownMenuItem(text = { Text("导出对话") }, onClick = { extras = false; exportText = session.exportMarkdown(); export.launch("对话-${session.id.take(8)}.md") })
                                DropdownMenuItem(text = { Text(if (session.archived) "移出归档" else "归档对话") }, enabled = session.status != SessionStatus.RUNNING && question == null,
                                    onClick = { extras = false; controller.archiveSession(session.id, !session.archived) })
                                DropdownMenuItem(text = { Text("删除对话", color = MaterialTheme.colorScheme.error) }, enabled = session.status != SessionStatus.RUNNING, onClick = { extras = false; deleting = true })
                            }
                        }
                    }
                    if (listening) Column(Modifier.weight(1f).align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.recorder.phase + " · ${voice.recorder.elapsedSeconds} 秒", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(voice.recorder.transcript.ifBlank { "说完点右侧结束，文字可以再改" }, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    else BasicTextField(value = draft, onValueChange = { draft = it; controller.saveDraft(session?.id, question?.id, it) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).onFocusChanged { composing = it.isFocused }
                            .semantics { contentDescription = if (question != null) "回复助手的问题" else "消息" },
                        maxLines = 6, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner ->
                            if (draft.isEmpty()) Text(if (question != null) "直接回复…" else if (session?.fileChatDirectory != null) "询问此文件夹的内容…" else "给 Vela 发消息…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge)
                            inner()
                        })
                    if (listening) {
                        FilledTonalIconButton(onClick = { voice.recorder.stop() }, enabled = voice.recorder.phase == "正在听…",
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "结束听写" }) { AgentGlyph("stop") }
                    } else if (state.running && session?.status == SessionStatus.RUNNING && draft.isBlank() && visibleFiles.isEmpty()) {
                        FilledTonalIconButton(onClick = { controller.stop("已暂停") }, modifier = Modifier.size(48.dp).semantics { contentDescription = "停止" }) { AgentGlyph("stop") }
                    } else if (draft.isBlank() && visibleFiles.isEmpty()) {
                        FilledTonalIconButton(onClick = { focus.clearFocus(); voice.start() }, enabled = !importing && session?.archived != true,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "语音输入" }) { AgentGlyph("mic") }
                    } else FilledIconButton(onClick = {
                        focus.clearFocus()
                        val oldId = session?.id
                        val oldQuestion = question?.id
                        if (controller.sendMessage(draft.ifBlank { "请看看这些附件，概括主要内容。" }, settings, replaceCurrent = replaceCurrent && session?.status in setOf(SessionStatus.RUNNING, SessionStatus.PAUSED, SessionStatus.FAILED), mode = if (session == null && visibleFiles.isNotEmpty() && !useScreen) ConversationMode.CHAT else mode, attachments = visibleFiles)) {
                            controller.saveDraft(oldId, oldQuestion, ""); draft = ""
                            if (oldQuestion == null) { controller.saveAttachmentDraft(oldId, workspaceId, emptyList()); attachments = emptyList() }
                        }
                    }, enabled = !importing && draft.length <= 4000 && session?.archived != true,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "发送" }) { AgentGlyph("send", color = LocalContentColor.current) }
                }
                if (draft.length > 4000) Text("这条消息太长了，最多 4000 字。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                else if (question == null && draft.isNotBlank() && session?.status in setOf(SessionStatus.RUNNING, SessionStatus.PAUSED, SessionStatus.FAILED)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = replaceCurrent, onClick = { replaceCurrent = true }, label = { Text("改做这件事") })
                        FilterChip(selected = !replaceCurrent, onClick = { replaceCurrent = false }, label = { Text("做完再处理") })
                    }
                }
            }
        }
    }
    if (detail >= 0 && session != null) ModalBottomSheet(onDismissRequest = { onDetail(-1) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.fillMaxHeight(.92f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            PrimaryTabRow(selectedTabIndex = detail, containerColor = MaterialTheme.colorScheme.surface) {
                listOf(if (mode == ConversationMode.CHAT) "消息记录" else "进度", "记住的信息", "文件").forEachIndexed { index, title -> Tab(selected = index == detail, onClick = { onDetail(index) }, text = { Text(title) }) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when(detail) {
            2 -> {
                Text("此对话保存的文件", style = MaterialTheme.typography.titleMedium)
                if (session.savedFiles.isEmpty()) Text("还没有保存文件。可在回复的“更多”中选择“保存为文件”。")
                session.savedFiles.forEach { file ->
                    OutlinedButton(onClick = { onDetail(-1); onFiles(workspaceId) }, modifier = Modifier.fillMaxWidth()) { Text(file.name) }
                }
                TextButton(onClick = { onDetail(-1); onFiles(workspaceId) }) { Text("打开工作区文件") }
            }
            0 -> {
                Text(if (mode == ConversationMode.CHAT) "对话中的请求" else "接下来要做的事", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (mode == ConversationMode.CHAT) "查看每条请求的状态；从回复的“更多”中可以创建分支。" else "可以修改还没开始的步骤，正在做的不会受影响。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                session.tasks.forEachIndexed { index, task ->
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${task.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(task.status.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (task.stepsTaken > 0) {
                                Text("已记录 ${task.stepsTaken} 步，继续时从当前页面核对", style = MaterialTheme.typography.bodySmall)
                                task.progress.takeLast(4).forEach { step -> Text("${step.sequence}. ${step.intent.ifBlank { step.action }} · ${when(step.phase) { "prepared" -> "结果待核对"; "submitted" -> "已提交，需核对结果"; else -> "未执行" }}", style = MaterialTheme.typography.bodySmall) }
                            }
                            if (!state.running && task.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { editing = task }) { Text("编辑") }
                                    TextButton(onClick = { controller.changeTask(session.id, task.id, -1) }, enabled = index > 0) { Text("上移") }
                                    TextButton(onClick = { controller.changeTask(session.id, task.id, 1) }, enabled = index < session.tasks.lastIndex) { Text("下移") }
                                }
                            }
                        }
                    }
                }
            }
            1 -> {
                var memory by rememberSaveable(session.id, session.summary) { mutableStateOf(session.summary) }
                Text("记住的信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("这里保存你明确提供的事实与偏好，仅用于当前会话。模型观察显示在每一步的结果中。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(memory, { memory = it }, label = { Text("需要记住的事") }, minLines = 5,
                    enabled = !state.running, modifier = Modifier.fillMaxWidth())
                Button(onClick = { focus.clearFocus(); controller.updateContext(session.id, memory) }, enabled = !state.running) { Text("保存") }
                TextButton(onClick = { memory = ""; controller.updateContext(session.id, "") }, enabled = !state.running) { Text("清空记忆") }
                HorizontalDivider()
                TextButton(onClick = { deleting = true }, enabled = !state.running) { Text("删除对话") }
            }
                }
            }
        }
    }
    if (organizing && session != null) {
        var name by rememberSaveable(session.id) { mutableStateOf(session.title) }
        var folder by rememberSaveable(session.id) { mutableStateOf(session.folder) }
        var pinned by rememberSaveable(session.id) { mutableStateOf(session.pinned) }
        AlertDialog(onDismissRequest = { organizing = false }, title = { Text("命名与整理") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("对话名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(folder, { folder = it.take(30) }, label = { Text("文件夹") }, placeholder = { Text("留空则不分类") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("置顶", Modifier.weight(1f)); Switch(pinned, { pinned = it })
                }
            }
        }, confirmButton = { TextButton(onClick = {
            controller.organizeSession(session.id, name, folder, pinned); organizing = false
        }, enabled = name.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = { organizing = false }) { Text("取消") } })
    }
    editing?.let { task ->
        var text by rememberSaveable(task.id) { mutableStateOf(task.title) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("修改这一步") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, minLines = 3, label = { Text("想做什么") })
                TextButton(onClick = { controller.changeTask(session!!.id, task.id, 0); editing = null }) { Text("删除这一步") }
            } }, confirmButton = { TextButton(onClick = { controller.editTask(session!!.id, task.id, text); editing = null }, enabled = text.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
    }
    if (deleting && session != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这个对话？") },
        text = { Text("消息、步骤和记住的信息都会删除。") },
        confirmButton = { TextButton(onClick = { controller.deleteSession(session.id); deleting = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留") } })
}

@Composable
private fun Suggestion(title: String, subtitle: String, prompt: String, modifier: Modifier, onPick: (String) -> Unit) {
    Surface(onClick = { onPick(prompt) }, modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

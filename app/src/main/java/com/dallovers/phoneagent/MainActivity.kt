package com.dallovers.phoneagent

import android.content.Intent
import android.content.ClipboardManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as AgentApplication).controller.setForeground(true)
    }
    override fun onPause() {
        super.onPause()
        (application as AgentApplication).controller.setForeground(false)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSession(intent, (application as AgentApplication).controller)
    }
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        (application as AgentApplication).controller.dump(writer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val controller = (application as AgentApplication).controller
        openSession(intent, controller)
        setContent { AgentScreen(controller) }
    }

    private fun openSession(intent: Intent, controller: AgentController) {
        intent.getStringExtra("questionSessionId")?.let(controller::openQuestionSession)
            ?: intent.getStringExtra("sessionId")?.let(controller::selectSession)
    }
}

internal val AgentColors = lightColorScheme(
    primary = Color(0xFF4965AC), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EBFB), onPrimaryContainer = Color(0xFF243B72),
    background = Color(0xFFF7F8FA), surface = Color(0xFFFEFEFF),
    surfaceVariant = Color(0xFFEBEEF5), onSurface = Color(0xFF202634),
    onSurfaceVariant = Color(0xFF606A7D), outline = Color(0xFFC6CDDA), error = Color(0xFFAB3546),
)

internal val AgentDarkColors = darkColorScheme(
    primary = Color(0xFFA4BCF8), onPrimary = Color(0xFF1D3157),
    primaryContainer = Color(0xFF344260), onPrimaryContainer = Color(0xFFE3E9FC),
    background = Color(0xFF191C22), surface = Color(0xFF242830),
    surfaceVariant = Color(0xFF2D323C), onSurface = Color(0xFFE7EAF2),
    onSurfaceVariant = Color(0xFFB0B7C6), outline = Color(0xFF667187), error = Color(0xFFFFB1B9),
)

internal fun SessionStatus.label(): String = when (this) {
    SessionStatus.READY -> "待处理"
    SessionStatus.RUNNING -> "处理中"
    SessionStatus.NEEDS_USER -> "等你回答"
    SessionStatus.PAUSED -> "已暂停"
    SessionStatus.COMPLETED -> "已完成"
    SessionStatus.FAILED -> "遇到问题"
}

internal fun TaskStatus.label(): String = when (this) {
    TaskStatus.TODO -> "还没开始"
    TaskStatus.RUNNING -> "处理中"
    TaskStatus.NEEDS_USER -> "等你回答"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.SKIPPED -> "已跳过"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AgentScreen(controller: AgentController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val initial = remember { controller.loadSettings() }
    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var thinking by rememberSaveable { mutableStateOf(initial.thinking) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var keyPasteMessage by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var detail by rememberSaveable { mutableIntStateOf(-1) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var accessibilityHelp by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedPhone by rememberSaveable { mutableStateOf(false) }
    var appearance by remember { mutableStateOf(controller.appearance()) }
    var profiles by remember { mutableStateOf(controller.modelProfiles()) }
    var profileName by rememberSaveable { mutableStateOf("") }
    var workspacePicker by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var profileDelete by remember { mutableStateOf<ModelProfile?>(null) }
    var filesDirty by remember { mutableStateOf(false) }
    var filesBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var deleteIds by remember { mutableStateOf(emptySet<String>()) }
    val useProfile: (ModelProfile) -> Unit = { profile ->
        if (!state.running && controller.saveSettings(profile.settings)) {
            endpoint = profile.settings.endpoint; model = profile.settings.model
            key = profile.settings.apiKey; thinking = profile.settings.thinking; modelPicker = false
        }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerFocus = LocalFocusManager.current
    val uiScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = ModelSettings(endpoint.trim(), model.trim(), key, thinking)
    val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) uiScope.launch {
            val ok = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(controller.exportRunLogs().toByteArray(Charsets.UTF_8)) } ?: error("无法写入")
            } }.isSuccess
            Toast.makeText(context, if (ok) "诊断日志已导出" else "日志导出失败，请换一个位置", Toast.LENGTH_SHORT).show()
        }
    }
    BackHandler(enabled = tab != 0) { tab = 0 }
    LaunchedEffect(drawer.currentValue) { if (drawer.currentValue == DrawerValue.Closed) drawerFocus.clearFocus() }
    LaunchedEffect(state.selectedSessionId, state.questionNavigation) { tab = 0; detail = -1 }
    val useDark = appearance == "dark" || appearance == "system" && isSystemInDarkTheme()
    SideEffect {
        val bars = if (useDark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
    MaterialTheme(colorScheme = if (useDark) AgentDarkColors else AgentColors) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        if (deleteIds.isNotEmpty()) AlertDialog(onDismissRequest = { deleteIds = emptySet() }, title = { Text("删除 ${deleteIds.size} 个对话？") },
            text = { Text("会删除所选对话的消息、问题和进度。工作区文件会保留。") },
            confirmButton = { TextButton(onClick = { controller.deleteSessions(deleteIds); selectedIds = selectedIds - deleteIds; deleteIds = emptySet() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteIds = emptySet() }) { Text("取消") } })
        if (workspacePicker) WorkspacePickerSheet(controller, state, onDismiss = { workspacePicker = false })
        if (modelPicker) ModalBottomSheet(onDismissRequest = { modelPicker = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择模型", style = MaterialTheme.typography.titleLarge)
                Text("当前：${model.ifBlank { "尚未设置" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (profiles.isEmpty()) Text("还没有保存的配置，可以先到模型设置添加。")
                profiles.forEach { profile ->
                    OutlinedButton(onClick = { useProfile(profile) }, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(profile.name); Text(profile.settings.model, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = { modelPicker = false; tab = 1 }) { Text("管理模型配置") }
                Spacer(Modifier.height(20.dp))
            }
        }
        profileDelete?.let { profile -> AlertDialog(onDismissRequest = { profileDelete = null }, title = { Text("删除这套配置？") },
            text = { Text("只删除已保存的“${profile.name}”，当前正在使用的配置保留。") },
            confirmButton = { TextButton(onClick = { controller.removeModelProfile(profile.id); profiles = controller.modelProfiles(); profileDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { profileDelete = null }) { Text("取消") } }) }
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp)) {
                    Column(Modifier.fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("对话", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                            TextButton(onClick = { managing = !managing; selectedIds = emptySet() }) { Text(if (managing) "完成" else "管理") }
                        }
                        Button(onClick = { controller.selectSession(null); tab = 0; uiScope.launch { drawer.close() } }, modifier = Modifier.fillMaxWidth()) { Text("新对话") }
                        OutlinedTextField(search, { search = it }, placeholder = { Text("搜索对话") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("全部", "处理中", "等我回复", "已归档").forEachIndexed { index, label ->
                                FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = folderFilter == null, onClick = { folderFilter = null }, label = { Text("所有文件夹") })
                            FilterChip(selected = folderFilter == "", onClick = { folderFilter = "" }, label = { Text("未分类") })
                            state.sessions.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted().forEach { folder ->
                                FilterChip(selected = folderFilter == folder, onClick = { folderFilter = folder }, label = { Text(folder) })
                            }
                        }
                        val listed = state.sessions.sortedWith(compareByDescending<TaskSession> { it.pinned }.thenByDescending { it.updatedAt }).filter {
                            (folderFilter == null || it.folder == folderFilter) &&
                            (search.isBlank() || it.title.contains(search, true) || it.goal.contains(search, true) || it.messages.any { message -> message.text.contains(search, true) }) &&
                            (if (filter == 3) it.archived else !it.archived && (filter == 0 || filter == 1 && it.status == SessionStatus.RUNNING || filter == 2 && it.question != null))
                        }
                        if (listed.isEmpty()) Text("没有找到对话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listed, key = { it.id }) { session ->
                            SessionListItem(session, session.id == state.selectedSessionId, managing, session.id in selectedIds,
                                onOpen = { controller.selectSession(session.id); tab = 0; uiScope.launch { drawer.close() } },
                                onToggle = { if (session.status != SessionStatus.RUNNING) selectedIds = if (session.id in selectedIds) selectedIds - session.id else selectedIds + session.id },
                                onPin = { controller.organizeSession(session.id, session.title, session.folder, !session.pinned) },
                                onArchive = { controller.archiveSession(session.id, !session.archived) }, onDelete = { deleteIds = setOf(session.id) })
                        }
                        }
                        if (managing) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { selectedIds = listed.filter { it.status != SessionStatus.RUNNING }.map { it.id }.toSet() }) { Text("全选") }
                            TextButton(onClick = { deleteIds = selectedIds }, enabled = selectedIds.isNotEmpty()) { Text("删除（${selectedIds.size}）", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { tab = 2; uiScope.launch { drawer.close() } }) { Text("手机操控") }
                            TextButton(onClick = { tab = 5; uiScope.launch { drawer.close() } }) { Text("文件") }
                            TextButton(onClick = { tab = 3; uiScope.launch { drawer.close() } }) { Text("日志") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { tab = 1; uiScope.launch { drawer.close() } }) { Text("模型设置") }
                            TextButton(onClick = { tab = 4; uiScope.launch { drawer.close() } }) { Text("外观") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

        }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { if (tab == 5) filesBack?.invoke() else if (tab != 0) tab = 0 else uiScope.launch { drawer.open() } }, modifier = Modifier.size(48.dp).semantics { contentDescription = if(tab != 0) "返回" else "打开对话列表" }) {
                    AgentGlyph(if (tab != 0) "back" else "menu")
                }
                Surface(onClick = { if (tab == 0 && state.selectedSessionId != null) detail = 0 }, shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(when(tab) { 1 -> "模型设置"; 2 -> "手机操控"; 3 -> "运行记录"; 4 -> "外观"; 5 -> "文件"; else -> state.sessions.firstOrNull { it.id == state.selectedSessionId }?.title ?: "新对话" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(if(state.running) "正在处理" else if(state.connected) "本机已连接" else "操作服务未连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (tab == 0) FilledTonalIconButton(onClick = { controller.selectSession(null); detail = -1 }, modifier = Modifier.size(48.dp).semantics { contentDescription = "新对话" }) { AgentGlyph("new") }
                Box {
                    if (tab == 0) TextButton(onClick = { profiles = controller.modelProfiles(); modelPicker = true }) { Text(if (model.isBlank()) "选模型" else "模型 ▾") }
                    else IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "页面菜单" }) { AgentGlyph("more") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf("对话", "模型设置", "手机操控", "运行记录", "外观", "文件").forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label) }, enabled = !filesDirty, onClick = { tab = index; menu = false })
                        }
                    }
                }
            }
            if (tab == 0) {
                SessionWorkspace(controller, state, settings, onModel = { profiles = controller.modelProfiles(); modelPicker = true }, onPhone = { tab = 2 }, detail = detail, onDetail = { detail = it }, onWorkspace = { workspacePicker = true }, onFiles = { id -> controller.chooseWorkspace(id); tab = 5 })
            } else if (tab == 5) {
                key(state.activeWorkspaceId) {
                    WorkspaceFilesPage(Modifier.weight(1f), onClose = { tab = 0 }, onDirtyChanged = { filesDirty = it }, onBackHandler = { filesBack = it },
                        controller = controller, workspaceId = state.activeWorkspaceId,
                        workspaceName = state.workspaces.firstOrNull { it.id == state.activeWorkspaceId }?.name ?: "工作区",
                        onChooseWorkspace = { workspacePicker = true })
                }
            } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab == 2 && !state.connected) {
                    AgentCard("开启本机操作服务") {
                        Text("助手就在这台手机上运行。首次使用需要在系统无障碍页面开启“Vela 操作服务”。")
                        Text("OPPO 开启路径", style = MaterialTheme.typography.titleSmall)
                        Text("1. 打开下方的系统无障碍设置\n2. 点击“已下载的应用”\n3. 找到“Vela 操作服务”并开启\n4. 返回助手，确认顶部显示服务已连接")
                        Button(onClick = {
                            accessibilityHelp = try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                null
                            } catch (_: android.content.ActivityNotFoundException) {
                                "无法自动打开，请手动前往系统设置，搜索“无障碍”，再找到“Vela 操作服务”。"
                            } catch (_: SecurityException) {
                                "系统限制了此入口，请手动在系统设置中搜索“无障碍”。"
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开系统无障碍设置") }
                        Text("打开的是系统授权页面；开启后请返回本应用。其他品牌的入口可能叫“已安装的服务”。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        accessibilityHelp?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (tab == 2) AgentCard("任务中的提问") {
                    Text(state.overlayStatus)
                    Text("任务需要补充或遇到阻塞时，会在当前页面显示待答浮层；普通完成回复不会弹窗。")
                    state.pendingPhoneQuestion()?.let { pending ->
                        OutlinedButton(onClick = { controller.openQuestionSession(pending.id) }) { Text("打开待答会话") }
                        OutlinedButton(onClick = { controller.refreshQuestionOverlay() }, enabled = state.connected) { Text("重新显示待答浮层") }
                    }
                    OutlinedButton(onClick = { controller.createRecoveryPractice() }, enabled = state.connected && !state.running && state.sessions.none { it.question != null }) { Text("检查浮层与中断恢复") }
                }
                if (state.running) {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { controller.stop("会话已暂停") }) { Text("暂停任务") }
                }
                if (!state.running && tab in listOf(1, 2, 3) && state.status !in listOf("操作服务已连接", "请先开启操作服务", "回复已完成", "正在回复")) {
                    Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                when (tab) {
                    1 -> AgentCard("模型配置") {
                        Text("聊天可使用文字模型；手机助手需要支持图片输入的模型。兼容 OpenAI 聊天接口。")
                        if (profiles.isNotEmpty()) {
                            Text("已保存的配置", style = MaterialTheme.typography.titleSmall)
                            profiles.forEach { profile ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { useProfile(profile) }, enabled = !state.running, modifier = Modifier.weight(1f)) {
                                        Text(profile.name + " · " + profile.settings.model, maxLines = 2)
                                    }
                                    TextButton(onClick = { profileDelete = profile }, enabled = !state.running) { Text("删除") }
                                }
                            }
                        }
                        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, enabled = !state.running,
                            label = { Text("API 基础地址") }, placeholder = { Text("https://api.deepseek.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it }, enabled = !state.running,
                            label = { Text("模型名称") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = key, onValueChange = { key = it; keyPasteMessage = null }, enabled = !state.running,
                            label = { Text("API key") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row {
                                    TextButton(onClick = {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java).primaryClip
                                        val pasted = clipboard?.takeIf { it.itemCount > 0 }
                                            ?.getItemAt(0)?.text?.toString()?.trim()
                                        if (pasted.isNullOrEmpty()) {
                                            keyPasteMessage = "剪贴板里没有文字，请先复制密钥"
                                        } else {
                                            key = pasted
                                            keyPasteMessage = "密钥已粘贴，请保存配置"
                                        }
                                    }, enabled = !state.running) { Text("粘贴") }
                                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth())
                        keyPasteMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text("密钥加密保存在这台手机。", style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("深度思考", style = MaterialTheme.typography.titleSmall)
                                Text("DeepSeek 官方接口适用，判断更充分，等待也会更久。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = thinking, onCheckedChange = { thinking = it }, enabled = !state.running,
                                modifier = Modifier.semantics { contentDescription = "深度思考" })
                        }
                        Button(onClick = { controller.saveSettings(settings) }, enabled = !state.running,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存配置") }
                        HorizontalDivider()
                        OutlinedTextField(profileName, { profileName = it.take(40) }, label = { Text("配置名称") }, placeholder = { Text("比如：日常聊天、手机操控") }, enabled = !state.running,
                            supportingText = { Text("同名配置会更新，密钥仍加密保存在本机。") }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = {
                            if (controller.saveModelProfile(profileName, settings)) { profiles = controller.modelProfiles(); profileName = "" }
                        }, enabled = !state.running && profileName.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存为常用配置") }
                        OutlinedButton(onClick = {
                            if (controller.saveSettings(settings)) controller.testModel(settings)
                        }, enabled = !state.running && endpoint.isNotBlank() && model.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("测试图片请求") }
                    }
                    4 -> AgentCard("选择你喜欢的外观") {
                        Text("外观立即生效，重新打开应用也会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                            Surface(onClick = { controller.setAppearance(value); appearance = value }, shape = RoundedCornerShape(12.dp),
                                color = if (appearance == value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = appearance == value, onClick = null); Spacer(Modifier.width(12.dp)); Text(label)
                                }
                            }
                        }
                    }
                    2 -> {
                        AgentCard("让助手帮你操作手机") {
                            Text(if (state.connected) "本机操作服务已连接，可以读取屏幕并完成你交代的事。" else "开启本机操作服务后，就可以让助手帮忙看屏幕、点击和输入。")
                            Text("第一次用？先试试练习页", style = MaterialTheme.typography.titleSmall)
                            Text("填写一段文字，遇到问题先问你，再完成一次模拟发送。只更新本地计数。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ActionButton("试一下本地练习", !state.running && state.connected) {
                                context.startActivity(Intent(context, PracticeActivity::class.java))
                            }
                        }
                        AgentCard("操作权限") {
                            Text("屏幕读取、点击与输入都由本应用的“Vela 操作服务”提供。你可以随时在系统设置中关闭。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (state.connected) "操作服务已连接" else "操作服务未连接", color = MaterialTheme.colorScheme.primary)
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                ActionButton("允许任务提醒", !state.running) {
                                    (context as? ComponentActivity)?.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
                                }
                            }
                            Text("有问题等你回答、任务跑完时，允许助手提醒你。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { advancedPhone = !advancedPhone }) { Text(if (advancedPhone) "收起基础动作测试" else "基础动作测试") }
                        if (advancedPhone) {
                        AgentCard("检查基础动作") {
                            Text("动作延迟 2 秒执行，请先切换到练习页面。")
                            ActionButton("看看当前屏幕", !state.running && state.connected) { controller.captureScreen() }
                            ActionButton("打开系统设置", !state.running && state.connected) { controller.manualAction(AgentAction("open_settings", "打开设置")) }
                            ActionButton("点击屏幕中心", !state.running && state.connected) { controller.manualAction(AgentAction("tap", "点击中心", x = .5, y = .5)) }
                            ActionButton("长按屏幕中心", !state.running && state.connected) { controller.manualAction(AgentAction("long_press", "长按中心", x = .5, y = .5, duration = 800)) }
                            ActionButton("向下翻页", !state.running && state.connected) { controller.manualAction(AgentAction("scroll", "向下翻页", direction = "down")) }
                            ActionButton("向上翻页", !state.running && state.connected) { controller.manualAction(AgentAction("scroll", "向上翻页", direction = "up")) }
                            ActionButton("返回上一页", !state.running && state.connected) { controller.manualAction(AgentAction("back", "返回")) }
                            ActionButton("回到桌面", !state.running && state.connected) { controller.manualAction(AgentAction("home", "回桌面")) }
                            ActionButton("在已聚焦输入框填写测试文字", !state.running && state.connected) {
                                controller.manualAction(AgentAction("type_text", "填写测试文字", text = "手机助手 Hello"))
                            }
                        }
                        ScreenshotCard(state, controller::clearScreenshot)
                        }
                    }
                    3 -> AgentCard("保存在手机的运行日志") {
                        val savedLogs by produceState(initialValue = emptyList<String>(), key1 = tab) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { controller.recentRunLogs().asReversed() }
                        }
                        Text("每一步的观察、决策、提交状态和服务连接会自动保存，最多保留 14 天、约 2 MB。不会保存密钥、输入正文、原始推理或截图。")
                        Text("当前 ${controller.runLogSize()} 字节", style = MaterialTheme.typography.bodySmall)
                        controller.runLogError()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = { exportLogs.launch("PhoneAgent-运行日志.jsonl") }, enabled = !state.running) { Text("导出诊断日志") }
                        Text("本次运行详情", style = MaterialTheme.typography.titleMedium)
                        if (state.logs.isEmpty()) Text("执行任务后，这里显示最近步骤和错误信息。")
                        state.logs.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = { controller.clearLogs() }, enabled = !state.running) { Text("清空本次显示") }
                        HorizontalDivider()
                        Text("最近保存的诊断事件", style = MaterialTheme.typography.titleMedium)
                        savedLogs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
}
}

@Composable
private fun AgentCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if(enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScreenshotCard(state: AgentState, onClear: () -> Unit) {
    val bitmap = state.screenshot ?: return
    val orientation = if (bitmap.width > bitmap.height) "横屏" else "竖屏"
    AgentCard("最近截图 · $orientation · ${bitmap.width} × ${bitmap.height}") {
        Image(bitmap.asImageBitmap(), contentDescription = "手机最近一次屏幕截图",
            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp))
        TextButton(onClick = onClear, enabled = !state.running) { Text("清除截图预览") }
    }
}

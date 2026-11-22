package com.dallovers.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONException

data class AgentState(
    val connected: Boolean = false,
    val running: Boolean = false,
    val status: String = "请先开启操作服务",
    val logs: List<String> = emptyList(),
    val screenshot: Bitmap? = null,
    val step: Int = 0,
    val sessions: List<TaskSession> = emptyList(),
    val selectedSessionId: String? = null,
    val streamingText: String = "",
    val streamingSessionId: String? = null,
    val workspaces: List<WorkspaceInfo> = listOf(WorkspaceInfo(WorkspacePaths.DEFAULT, "本机工作区")),
    val activeWorkspaceId: String = WorkspacePaths.DEFAULT,
    val overlayStatus: String = "没有待答问题",
    val questionNavigation: Int = 0,
)

internal fun AgentState.pendingPhoneQuestion(): TaskSession? = sessions.firstOrNull {
    it.mode == ConversationMode.PHONE && it.status == SessionStatus.NEEDS_USER && it.question != null
}

class AgentController(context: Context) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(context)
    private val drafts = context.getSharedPreferences("conversation_drafts", Context.MODE_PRIVATE)
    private val sessionRepository = SessionRepository(context)
    private val workspaceStore = WorkspaceStore(context)
    private val notifications = AgentNotifications(context)
    private val journal = RunJournal(java.io.File(context.filesDir, "run_logs"))
    internal val media = DeviceAttachments(appContext)
    private val modelClient = ModelClient(media::dataUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val restoredSessions = sessionRepository.load()
    init {
        if (sessionRepository.loadError == null) runCatching {
            val keptDocuments = restoredSessions.flatMap { it.tasks }.flatMap { it.attachments }.map { it.document }.filter(String::isNotEmpty).toMutableSet()
            val kept = restoredSessions.flatMap { it.tasks }.flatMap { it.attachments }.flatMap { it.pages }.toMutableSet()
            drafts.all.filterKeys { it.startsWith("files:") }.values.forEach { value ->
                AttachmentCodec.decode(org.json.JSONArray(value as String)).forEach { kept.addAll(it.pages); if (it.document.isNotEmpty()) keptDocuments.add(it.document) }
            }
            media.prune(kept, keptDocuments)
        }
    }
    private val mutableState = MutableStateFlow(AgentState(sessions = restoredSessions, workspaces = workspaceStore.list(), activeWorkspaceId = workspaceStore.current(),
        selectedSessionId = (restoredSessions.firstOrNull { it.question != null } ?: restoredSessions.lastOrNull { !it.archived })?.id,
        status = sessionRepository.loadError ?: "请先开启操作服务"))
    val state = mutableState.asStateFlow()
    private var service: PhoneAccessibilityService? = null
    private var operation: Job? = null
    private var generation = 0L
    private var answerResume: Job? = null
    private var activeSessionId: String? = null

    private var trackedQuestionId: String? = null
    private var notificationUrgent: Boolean? = null
    private var questionWindowEvent: String? = null
    private var reminderJob: Job? = null
    /** 界面是否在前台。只有前台且正看着这条会话时才不打扰。 */
    private var foreground = false
    /** 应用内通知开关，默认开。关掉后两类通知都不发，系统权限本身不动。 */
    private var notificationsOn = settingsStore.notificationsEnabled()

    init {
        journal.record("process_started", count = restoredSessions.count { it.status == SessionStatus.PAUSED })
        trackPendingQuestion()
    }
    /** 由 Activity 的 onResume / onPause 同步；回到前台顺手收掉上次留下的完成通知。 */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (visible) notifications.clearCompleted()
    }
    fun notificationsEnabled(): Boolean = notificationsOn
    /** 关掉时把已经挂着的通知一起收掉，不留一条点不动的旧提醒。 */
    fun setNotificationsEnabled(enabled: Boolean) {
        notificationsOn = enabled
        runCatching { settingsStore.setNotificationsEnabled(enabled) }.onFailure { status(it.message ?: "通知设置保存失败") }
        if (!enabled) notifications.clearAll()
        journal.record("notification_setting", phase = if (enabled) "enabled" else "disabled")
    }
    fun exportRunLogs(): String = journal.export()
    fun recentRunLogs(): List<String> = journal.export().lineSequence().filter(String::isNotBlank).toList().takeLast(60).mapNotNull { line ->
        runCatching { org.json.JSONObject(line).let { row ->
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(row.getLong("time")))
            "$time  ${row.getString("event")}  ${row.optString("action")} ${row.optString("phase")} ${row.optString("package")}".trim()
        } }.getOrNull()
    }
    fun runLogSize(): Long = journal.size()
    fun runLogError(): String? = journal.error
    internal fun voiceEvent(event: String, count: Int) {
        require(event in setOf("voice_started", "voice_cancelled", "voice_decoded", "voice_failed"))
        scope.launch(Dispatchers.IO) { journal.record(event, action = "sensevoice", count = count) }
    }
    private fun trackPendingQuestion() {
        val pending = state.value.pendingPhoneQuestion()
        val id = pending?.question?.id
        if (trackedQuestionId == id) return
        trackedQuestionId = id
        questionWindowEvent = null
        notificationUrgent = null
        reminderJob?.cancel()
        if (pending == null) {
            notifications.clearQuestion()
            overlayStatus("没有待答问题")
            return
        }
        overlayStatus(if (state.value.connected) "正在显示待答问题" else "操作服务未连接，待答问题已保留")
        reminderJob = scope.launch {
            delay(1500)
            if (trackedQuestionId == id) postQuestionReminder(pending,
                urgent = questionWindowEvent !in setOf("overlay_shown", "overlay_collapsed"))
        }
    }

    private fun postQuestionReminder(session: TaskSession, urgent: Boolean, force: Boolean = false) {
        if (!notificationsOn) {
            journal.record("question_notification", session.id, session.question?.taskId.orEmpty(), phase = "disabled")
            return
        }
        if (!force && (notificationUrgent == urgent || notificationUrgent == true)) return
        val result = notifications.showQuestion(session.id, urgent)
        notificationUrgent = urgent
        journal.record("question_notification", session.id, session.question?.taskId.orEmpty(),
            action = if (urgent) "fallback" else "normal", phase = result)
    }

    internal fun questionWindowStatus(session: TaskSession, message: String, event: String) {
        if (session.question?.id != trackedQuestionId || questionWindowEvent == event) return
        questionWindowEvent = event
        journal.record(event, session.id, session.question?.taskId.orEmpty())
        overlayStatus(message)
        if (event in setOf("overlay_failed", "overlay_not_visible")) postQuestionReminder(session, urgent = true)
    }

    internal fun overlayStatus(message: String) {
        mutableState.update { it.copy(overlayStatus = message) }
    }

    fun refreshQuestionOverlay() {
        service?.refreshQuestionControl()
        state.value.pendingPhoneQuestion()?.let { postQuestionReminder(it, urgent = questionWindowEvent !in
            setOf("overlay_shown", "overlay_collapsed"), force = true) }
    }

    fun openQuestionSession(id: String) {
        if (state.value.sessions.any { it.id == id && it.question != null }) {
            mutableState.update { it.copy(selectedSessionId = id, questionNavigation = it.questionNavigation + 1) }
        }
    }

    fun loadSettings(): ModelSettings = settingsStore.load()
    fun workspaceFiles(id: String) = workspaceStore.files(id)
    private fun fileTools(session: TaskSession, task: SessionTask): FileTools {
        val reader = DocumentReader(appContext, media)
        val readDocument = reader::read
        val base: FileTools = if (session.fileChatDirectory != null) {
            WorkspaceFileTools(workspaceFiles(session.workspaceId), session.fileChatDirectory, readDocument)
        } else {
            val storage = PhoneStorage(appContext)
            val shared = if (android.os.Environment.isExternalStorageManager()) storage.volumes().associate { volume ->
                volume.id to {
                    val folder = PhoneFolder(volume.id, "")
                    val root = storage.root(folder)
                    WorkspaceFileTools(WorkspaceFiles(root, "") {
                        check(storage.root(folder) == root) { "存储位置已变化" }
                    }, "", readDocument)
                }
            } else emptyMap()
            ConversationFileTools({ WorkspaceFileTools(workspaceFiles(session.workspaceId), "", readDocument) }, shared)
        }
        val documents = (session.tasks.filter { it.status == TaskStatus.COMPLETED } + task)
            .flatMap { it.attachments }.filter { it.document.isNotEmpty() }.associateBy { it.name }.values.toList()
        return if (documents.isEmpty()) base else AttachedDocumentTools(base, documents, media, reader)
    }

    internal fun openPhoneFolder(folder: PhoneFolder) {
        val info = workspaceStore.openFolder(folder)
        mutableState.update { it.copy(workspaces = workspaceStore.list(), activeWorkspaceId = info.id) }
    }
    fun chooseWorkspace(id: String) {
        workspaceStore.choose(id)
        mutableState.update { it.copy(activeWorkspaceId = id) }
    }
    fun createWorkspace(name: String): Result<Unit> = runCatching {
        val info = workspaceStore.create(name)
        mutableState.update { it.copy(workspaces = workspaceStore.list(), activeWorkspaceId = info.id) }
    }
    suspend fun readAttachments(workspaceId: String, names: List<String>): List<TextAttachment> = withContext(Dispatchers.IO) {
        require(names.size <= TextAttachment.MAX_FILES) { "最多选择 4 个附件" }
        val files = workspaceFiles(workspaceId)
        val items = names.map { name ->
            val text = runCatching { files.read(name) }.getOrNull()?.takeIf { it.toByteArray(Charsets.UTF_8).size <= TextAttachment.MAX_BYTES }
            if (text != null) TextAttachment.snapshot(workspaceId, name, text)
            else media.import(android.net.Uri.fromFile(files.existingFile(name)), workspaceId, name.substringAfterLast('/')).copy(name = name)
        }
        TextAttachment.validateBatch(items, workspaceId)
        items
    }
    fun loadAttachmentDraft(sessionId: String?, workspaceId: String): List<TextAttachment> = runCatching {
        val rows = org.json.JSONArray(drafts.getString("files:${sessionId ?: "new"}:$workspaceId", "[]"))
        AttachmentCodec.decode(rows).also { TextAttachment.validateBatch(it, workspaceId) }
    }.getOrDefault(emptyList())
    fun saveAttachmentDraft(sessionId: String?, workspaceId: String, items: List<TextAttachment>) {
        TextAttachment.validateBatch(items, workspaceId)
        val key = "files:${sessionId ?: "new"}:$workspaceId"
        drafts.edit().apply { if (items.isEmpty()) remove(key) else putString(key, AttachmentCodec.encode(items).toString()) }.apply()
    }
    fun startFileChat(workspaceId: String, directory: String) {
        check(sessionRepository.loadError == null) { "会话记录无法写入" }
        if (directory.isNotEmpty()) validateRelativePath(directory)
        val workspace = state.value.workspaces.first { it.id == workspaceId }
        val label = directory.substringAfterLast('/').ifBlank { workspace.name }
        val session = TaskSession(title = "询问 $label".take(80), goal = "", tasks = emptyList(),
            mode = ConversationMode.CHAT, workspaceId = workspaceId, fileChatDirectory = directory)
        persistSessions(state.value.sessions + session)
        selectSession(session.id)
    }

    suspend fun referenceFile(workspaceId: String, name: String) {
        check(sessionRepository.loadError == null) { "会话记录无法写入" }
        val items = readAttachments(workspaceId, listOf(name))
        val session = TaskSession(title = "关于 $name".take(80), goal = "", tasks = emptyList(), mode = ConversationMode.CHAT, workspaceId = workspaceId)
        persistSessions(state.value.sessions + session)
        saveAttachmentDraft(session.id, workspaceId, items)
        selectSession(session.id)
    }
    suspend fun saveReplyFile(sessionId: String, messageId: String, partIndex: Int, name: String) {
        check(sessionRepository.loadError == null) { "会话记录无法写入" }
        val session = state.value.sessions.first { it.id == sessionId }
        val message = session.conversationMessages().first { it.id == messageId && it.role == "assistant" }
        val text = ReplyFiles.parts(message.text).getOrNull(partIndex)?.text ?: error("没有找到这段内容")
        WorkspaceNames.validate(name)
        val path = childPath(session.fileChatDirectory.orEmpty(), name)
        withContext(Dispatchers.IO) { workspaceFiles(session.workspaceId).save(path, text, create = true) }
        val record = SavedReplyFile(path, TextAttachment.hash(text), messageId)
        updateSession(sessionId) { it.copy(savedFiles = it.savedFiles + record) }
    }
    fun modelProfiles() = runCatching { settingsStore.profiles() }.onFailure { status("已保存的模型配置无法读取，原数据已保留") }.getOrDefault(emptyList())
    fun saveModelProfile(name: String, settings: ModelSettings): Boolean = runCatching {
        check(!state.value.running) { "请等当前回复结束后再切换模型" }
        settings.validate(); settingsStore.saveProfile(name, settings)
    }.onFailure { status(it.message ?: "保存失败") }.isSuccess
    fun removeModelProfile(id: String) { if (!state.value.running) runCatching { settingsStore.removeProfile(id) }.onFailure { status("删除配置失败，原数据已保留") } }
    fun appearance() = settingsStore.appearance()
    fun setAppearance(value: String) = settingsStore.setAppearance(value)

    fun setConversationMode(id: String, mode: ConversationMode) {
        if (state.value.running) return
        updateSession(id) { if (it.canChangeMode()) it.copy(mode = mode) else it }
    }

    fun organizeSession(id: String, title: String, folder: String, pinned: Boolean) {
        if (title.isBlank()) return
        updateSession(id) { it.copy(title = title.trim().take(80), folder = folder.trim().take(30), pinned = pinned) }
    }

    fun branchReply(sessionId: String, messageId: String, retry: Boolean, settings: ModelSettings) {
        if (state.value.running || sessionRepository.loadError != null) return
        val original = state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val branch = runCatching { original.chatBranch(messageId, retry) }.getOrElse { status(it.message ?: "无法创建分支"); return }
        persistSessions(state.value.sessions + branch)
        selectSession(branch.id)
        if (retry) runSession(branch.id, settings)
    }

    fun loadDraft(sessionId: String?, questionId: String? = null): String =
        drafts.getString("${sessionId ?: "new"}:${questionId.orEmpty()}", "").orEmpty()

    fun saveDraft(sessionId: String?, questionId: String?, text: String) {
        val key = "${sessionId ?: "new"}:${questionId.orEmpty()}"
        drafts.edit().apply { if (text.isEmpty()) remove(key) else putString(key, text.take(4000)) }.apply()
    }

    fun sendMessage(text: String, settings: ModelSettings, replaceCurrent: Boolean = false, mode: ConversationMode = ConversationMode.PHONE, attachments: List<TextAttachment> = emptyList()): Boolean {
        if (text.isBlank() || text.length > 4000 || sessionRepository.loadError != null) return false
        val selected = state.value.sessions.firstOrNull { it.id == state.value.selectedSessionId }
        if (selected?.archived == true) return false
        val workspaceId = selected?.workspaceId ?: state.value.activeWorkspaceId
        if (runCatching { TextAttachment.validateBatch(attachments, workspaceId) }.isFailure) return false
        if (attachments.isNotEmpty() && selected?.question != null) return false
        if (selected?.question != null) return answerQuestion(selected.id, selected.question.id, text)
        if (replaceCurrent && selected != null && selected.status in setOf(SessionStatus.RUNNING, SessionStatus.PAUSED, SessionStatus.FAILED)) {
            if (selected.status == SessionStatus.RUNNING) stop("已收到新要求，正在调整")
            val latest = state.value.sessions.first { it.id == selected.id }.replaceCurrentRequest(text, attachments)
            persistSessions(state.value.sessions.map { if (it.id == latest.id) latest else it })
            runSession(latest.id, settings)
            return true
        }
        val session = if (selected == null) {
            val task = SessionTask(title = text.trim(), completion = text.trim(), attachments = attachments)
            TaskSession(title = text.trim().lineSequence().first().take(40), goal = text.trim(), tasks = listOf(task), mode = mode, workspaceId = workspaceId,
                messages = listOf(ConversationMessage(role = "user", text = text.trim(), taskId = task.id)))
        } else selected.queueMessage(text, attachments)
        persistSessions(state.value.sessions.filterNot { it.id == session.id } + session)
        selectSession(session.id)
        if (selected?.status in setOf(SessionStatus.PAUSED, SessionStatus.FAILED)) {
            updateSession(session.id) { it.copy(notice = "消息已记下。点“继续”后一起处理。") }
        } else if (!state.value.running) runSession(session.id, settings)
        else if (selected?.status != SessionStatus.RUNNING) updateSession(session.id) { it.copy(notice = "消息已记下。另一个对话还在处理，稍后点“继续”。") }
        return true
    }

    fun createSession(title: String, goal: String, taskLines: String): Boolean {
        if (sessionRepository.loadError != null) { status(sessionRepository.loadError!!); return false }
        val tasks = parseTaskPlan(taskLines)
        if (title.isBlank() || goal.isBlank() || tasks.isEmpty()) return false
        val session = TaskSession(title = title.trim().take(80), goal = goal.trim().take(2000), tasks = tasks)
        persistSessions(mutableState.value.sessions + session)
        mutableState.update { it.copy(selectedSessionId = session.id) }
        return true
    }

    fun selectSession(id: String?) { mutableState.update { it.copy(selectedSessionId = id) } }

    fun archiveSession(id: String, archived: Boolean) {
        updateSession(id) { if (it.status == SessionStatus.RUNNING || it.question != null) it else it.copy(archived = archived) }
    }

    fun deleteSession(id: String) {
        if (mutableState.value.sessions.firstOrNull { it.id == id }?.status == SessionStatus.RUNNING) return
        drafts.edit().apply { drafts.all.keys.filter { it.startsWith("$id:") || it.startsWith("files:$id:") }.forEach { remove(it) } }.apply()
        val remaining = mutableState.value.sessions.filterNot { it.id == id }
        persistSessions(remaining)
        mutableState.update { it.copy(selectedSessionId = if (it.selectedSessionId == id) remaining.firstOrNull()?.id else it.selectedSessionId) }
    }

    fun deleteSessions(ids: Set<String>) {
        val removable = state.value.sessions.filter { it.id in ids && it.status != SessionStatus.RUNNING }.map { it.id }.toSet()
        if (removable.isEmpty()) return
        val remaining = state.value.sessions.filterNot { it.id in removable }
        persistSessions(remaining)
        drafts.edit().apply { drafts.all.keys.filter { k -> removable.any { k.startsWith("$it:") || k.startsWith("files:$it:") } }.forEach { remove(it) } }.apply()
        mutableState.update { it.copy(selectedSessionId = it.selectedSessionId.takeUnless { id -> id in removable }) }
        journal.record("sessions_deleted", count = removable.size)
    }

    fun editTask(sessionId: String, taskId: String, title: String) {
        if (title.isBlank()) return
        updateSession(sessionId) { session ->
            if (session.status == SessionStatus.RUNNING) session else session.copy(tasks = session.tasks.map {
                if (it.id == taskId && it.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED)) it.copy(title = title.trim().take(500), completion = title.trim().take(500)) else it
            })
        }
    }

    fun addTask(sessionId: String, title: String) {
        if (title.isBlank()) return
        updateSession(sessionId) { it.copy(status = if (it.status == SessionStatus.COMPLETED) SessionStatus.PAUSED else it.status, tasks = it.tasks + SessionTask(title = title.trim().take(500), completion = title.trim().take(500))) }
    }

    fun updateContext(sessionId: String, summary: String) {
        updateSession(sessionId) { if (it.status == SessionStatus.RUNNING) it else it.copy(summary = summary.take(6000)) }
    }

    fun changeTask(sessionId: String, taskId: String, direction: Int) {
        updateSession(sessionId) { session ->
            val tasks = session.tasks.toMutableList()
            val index = tasks.indexOfFirst { it.id == taskId }
            if (session.status == SessionStatus.RUNNING || index < 0 || tasks[index].status !in setOf(TaskStatus.TODO, TaskStatus.PAUSED)) return@updateSession session
            if (direction == 0) tasks.removeAt(index) else {
                val destination = index + direction
                if (destination !in tasks.indices || tasks[destination].status !in setOf(TaskStatus.TODO, TaskStatus.PAUSED)) return@updateSession session
                val moving = tasks.removeAt(index)
                tasks.add(destination, moving)
            }
            session.copy(tasks = tasks)
        }
    }

    fun createPracticeSession() {
        if (state.value.running || state.value.sessions.any { it.question != null }) return
        val session = TaskSession(title = "本地多任务问答练习", goal = "仅在定位练习页执行本地测试，不操作其他应用。",
            constraints = "practice-clarification-v1",
            tasks = listOf(SessionTask(title = "填写用户选择的练习文字，先不要发送", completion = "输入框包含用户答复中的练习文字"),
                SessionTask(title = "点击一次本地模拟发送并核对计数", completion = "已模拟发送 1 次且输入框为空")))
        persistSessions(state.value.sessions + session)
        selectSession(session.id)
        runSession(session.id, loadSettings())
    }

    fun createOverlayAnswerPractice() {
        if (state.value.running || state.value.sessions.any { it.question != null }) return
        val task = SessionTask(title = "跨应用浮层答复练习，仅重新读取页面", status = TaskStatus.NEEDS_USER)
        val question = UserQuestion(taskId = task.id, prompt = "你想怎样继续？", reason = "这是本地练习；答复后只读取当前页面，不点击、不输入。",
            options = listOf("继续查看", "先核对一下"))
        val session = TaskSession(title = "浮层答复练习", goal = task.title, constraints = "overlay-answer-practice-v1",
            tasks = listOf(task), status = SessionStatus.NEEDS_USER, question = question,
            messages = listOf(ConversationMessage(role = "user", text = task.title, taskId = task.id),
                ConversationMessage(role = "assistant", text = question.prompt, taskId = task.id)))
        persistSessions(state.value.sessions + session); selectSession(session.id)
    }

    /** Uses the same interruption path as a real blocked action; no model or external action. */
    fun createRecoveryPractice() {
        if (state.value.running || state.value.sessions.any { it.question != null }) return
        val task = SessionTask(title = "中断恢复与浮层练习", status = TaskStatus.RUNNING,
            progress = listOf(TaskStep(1, "wait", "只读练习检查点", "submitted", "com.android.settings", "practice")), stepsTaken = 1)
        val session = TaskSession(title = "恢复练习", goal = task.title, constraints = "overlay-answer-practice-v1", tasks = listOf(task), status = SessionStatus.RUNNING)
        persistSessions(state.value.sessions + session); selectSession(session.id)
        requestRecovery(session.id, "练习：动作未能推进，检查点已经保存。")
    }

    private fun requestRecovery(sessionId: String, reason: String, interruptedTaskId: String? = null) {
        val session = state.value.sessions.firstOrNull { it.id == sessionId } ?: return
        val task = session.tasks.firstOrNull {
            it.status in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED) &&
                (interruptedTaskId == null || it.id == interruptedTaskId)
        } ?: return
        val question = UserQuestion(taskId = task.id, prompt = "这一步卡住了，要怎样继续？", reason = reason.take(500) + " 已保留进度，也可以自己操作到合适页面后告诉我。",
            options = listOf("核对当前页面后继续", "我已调整好页面，请继续"))
        updateSession(sessionId) { it.copy(status = SessionStatus.NEEDS_USER, question = question, notice = reason.take(500),
            messages = it.conversationMessages() + ConversationMessage(role = "assistant", text = question.prompt, taskId = task.id),
            tasks = it.tasks.map { t -> if (t.id == task.id) t.copy(status = TaskStatus.NEEDS_USER, lastIssue = reason.take(500)) else t }) }
        journal.record("recovery_question", session.id, task.id, phase = "needs_user", count = task.stepsTaken)
        status("这一步需要你帮忙，进度已保留")
    }

    fun answerQuestion(sessionId: String, questionId: String, answer: String): Boolean {
        if (answer.isBlank() || answer.length > 4000) return false
        val session = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: return false
        val answered = session.withAnswer(questionId, answer)
        if (answered == session) return false
        if (runCatching { updateSession(sessionId) { it.withAnswer(questionId, answer) } }.isFailure) {
            status("答复没有保存成功，请重试"); return false
        }
        service?.hideQuestionControl()
        saveDraft(sessionId, questionId, "")
        journal.record("user_answer", sessionId, session.question?.taskId.orEmpty())
        mutableState.update { it.copy(selectedSessionId = sessionId) }
        log("已收到当前会话的补充；浮层收起后重新读取屏幕并继续")
        answerResume?.cancel()
        answerResume = scope.launch {
            // Let the overlay and its IME leave the compositor before taking the next frame.
            delay(450)
            while (state.value.running) delay(100)
            val latest = state.value.sessions.firstOrNull { it.id == sessionId } ?: return@launch
            if (latest.status == SessionStatus.PAUSED && latest.question == null &&
                latest.tasks.any { it.id == session.question?.taskId && it.status == TaskStatus.PAUSED }) {
                runSession(sessionId, loadSettings())
            }
        }
        return true
    }

    fun dismissQuestion(sessionId: String, questionId: String, outcome: String) {
        val session = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.question?.id != questionId) return
        answerResume?.cancel()
        updateSession(sessionId) { it.copy(status = SessionStatus.PAUSED,
            question = null, tasks = it.tasks.map { task -> if (task.id == session.question?.taskId)
                task.copy(status = if (outcome == "跳过") TaskStatus.SKIPPED else TaskStatus.PAUSED) else task }) }
        service?.hideQuestionControl()
    }

    private fun runChat(sessionId: String, settings: ModelSettings) {
        val invalid = runCatching { settings.validate() }.exceptionOrNull()
        if (invalid != null) { updateSession(sessionId) { it.copy(notice = invalid.message.orEmpty()) }; return }
        launchOperation("正在回复", allowPendingQuestion = true) {
            activeSessionId = sessionId
            while (true) {
                ensureActive()
                val session = state.value.sessions.firstOrNull { it.id == sessionId } ?: return@launchOperation
                val task = session.tasks.firstOrNull { it.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED) }
                    ?: run { updateSession(sessionId) { it.copy(status = SessionStatus.COMPLETED) }; status("回复已完成"); return@launchOperation }
                updateSession(sessionId) { it.copy(status = SessionStatus.RUNNING, notice = "",
                    tasks = it.tasks.map { t -> if (t.id == task.id) t.copy(status = TaskStatus.RUNNING) else t }) }
                val token = generation
                mutableState.update { it.copy(streamingText = "", streamingSessionId = sessionId) }
                val fileTools = withContext(Dispatchers.IO) { fileTools(session, task) }
                val answer = ChatClient(media::dataUrl).reply(settings, session, task, fileTools) { text ->
                    scope.launch { if (generation == token) mutableState.update { it.copy(streamingText = text) } }
                }
                ensureActive()
                updateSession(sessionId) { it.completeTask(task.id, answer) }
                mutableState.update { it.copy(streamingText = "", streamingSessionId = null) }
            }
        }
    }

    fun runSession(sessionId: String, settings: ModelSettings, fromHome: Boolean = false) {
        val session = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (session.status == SessionStatus.RUNNING || session.status == SessionStatus.NEEDS_USER || session.tasks.none { it.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED) }) return
        if (session.archived) return
        if (session.mode == ConversationMode.CHAT) { runChat(sessionId, settings); return }
        val reason = when {
            state.value.running -> "另一个任务还在处理，请稍后继续。"
            state.value.sessions.any { it.question != null } -> "另一个对话正在等你回答，请先处理那个问题。"
            !state.value.connected -> "先到“手机操控”打开本机操作服务，再回来继续。"
            else -> runCatching { settings.validate() }.exceptionOrNull()?.message
        }
        if (reason != null) { updateSession(sessionId) { it.copy(notice = reason) }; status(reason); return }
        launchOperation("正在恢复会话") {
            activeSessionId = sessionId
            journal.record("task_resumed", sessionId)
            settings.validate()
            val activeService = requireService()
            activeService.showStopControl()
            updateSession(sessionId) { it.copy(status = SessionStatus.RUNNING, question = null, notice = "",
                tasks = it.tasks.map { t -> if (t.status == TaskStatus.PAUSED && t.lastIssue.isBlank()) t.copy(lastIssue = session.notice.take(500)) else t }) }
            if (fromHome) { activeService.execute(AgentAction("home", "从桌面开始")); delay(1000) }
            var history = emptyList<String>()
            var inputFailures = 0
            var rejectedActions = 0
            var lastTapObservation: ScreenObservation? = null
            var unchangedClicks = 0
            while (true) {
                ensureActive()
                val latest = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: error("会话已删除")
                val task = latest.tasks.firstOrNull { it.status in setOf(TaskStatus.TODO, TaskStatus.PAUSED, TaskStatus.RUNNING) }
                    ?: run { updateSession(sessionId) { it.copy(status = SessionStatus.COMPLETED) }; status("会话已完成"); return@launchOperation }
                updateSession(sessionId) { it.copy(status = SessionStatus.RUNNING,
                    tasks = it.tasks.map { item -> if (item.id == task.id) item.copy(status = TaskStatus.RUNNING, startedAt = item.startedAt.takeIf { stamp -> stamp > 0 } ?: System.currentTimeMillis()) else item }) }
                val progressGuard = ProgressGuard()
                modelClient.beginTask()
                history = emptyList()
                inputFailures = 0
                rejectedActions = 0
                lastTapObservation = null
                unchangedClicks = 0
                val availableFiles = withContext(Dispatchers.IO) { fileTools(latest, task) }
                var taskSteps = 0
                var taskComplete = false
                while (!taskComplete) {
                    ensureActive()
                    val live = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: error("会话已删除")
                    if (++taskSteps > 60) error("这件事已尝试 60 步仍未完成，已暂停，请补充更具体的要求")
                    val frame = capture()
                    val observed = frame.observation
                    journal.record("observation", sessionId, task.id, packageName = observed?.packageName.orEmpty(), count = observed?.targets?.size ?: 0)
                    if (latest.constraints == "overlay-answer-practice-v1") {
                        val answer = live.conversationMessages().lastOrNull { it.role == "user" && it.taskId == task.id }?.text.orEmpty()
                        updateSession(sessionId) { it.completeTask(task.id, "已收到答复：$answer。当前页面已重新读取；本次练习未执行点击或输入。") }
                        taskComplete = true
                        continue
                    }
                    if (latest.constraints == "practice-clarification-v1" && observed?.packageName != "com.dallovers.phoneagent") error("练习会话仅允许在本应用内执行，请返回定位练习后继续")
                    if (observed != null) {
                        val visits = progressGuard.observe(observed)
                        if (progressGuard.feedback.isNotBlank()) history = (history + progressGuard.feedback).takeLast(8)
                        if (visits >= 6) error("多次尝试仍回到同一页面，已暂停，请补充要求或换一种方式")
                        if (visits >= 3) history = (history + "近期同一页面反复出现，请先核对是否已完成；无法推进则询问用户。").takeLast(8)
                    }
                    lastTapObservation?.let { before ->
                        val unchanged = observed != null && before.visiblySameAs(observed)
                        unchangedClicks = if (unchanged) unchangedClicks + 1 else 0
                        if (unchangedClicks >= 3) error("连续点击后页面没有变化，已停止，请检查目标")
                        if (unchanged) history = (history + "上次点击后可识别内容未变化，重新评估当前画面并避免重复猜测。").takeLast(8)
                    }
                    lastTapObservation = null
                    mutableState.update { it.copy(step = it.step + 1) }
                    val officialThinking = settings.thinking && runCatching { java.net.URI(settings.endpoint).host == "api.deepseek.com" }.getOrDefault(false)
                    status(if (officialThinking) "正在思考下一步 · ${task.title}" else "正在查看屏幕 · ${task.title}")
                    if (latest.constraints == "practice-clarification-v1" && latest.summary.isBlank()) {
                        val question = UserQuestion(taskId = task.id, prompt = "练习输入框应填写哪段文字？",
                            reason = "本地确定性歧义注入：存在两个候选内容，执行器尚未输入或点击。仅影响练习页。",
                            options = listOf("定位测试", "比赛演示"))
                        updateSession(sessionId) { it.copy(status = SessionStatus.NEEDS_USER, question = question,
                            messages = it.conversationMessages() + ConversationMessage(role = "assistant", text = question.prompt, taskId = task.id),
                            tasks = it.tasks.map { item -> if (item.id == task.id) item.copy(status = TaskStatus.NEEDS_USER) else item }) }
                        status("本地练习等待答复，所有自动操作已暂停")
                        return@launchOperation
                    }
                    val decision = try {
                        modelClient.nextDecision(settings, live.executionContext(live.tasks.first { it.id == task.id }) + "\n应用操作参考：" + AppPlaybooks.forScreen(observed?.packageName.orEmpty(), task.title) + "\n可直接打开的应用（open_app 仅接受此列表中的 package_name）：" +
                            org.json.JSONArray(activeService.launchableApps().map { (label, pkg) -> org.json.JSONObject().put("label", label).put("package_name", pkg) }), frame, history, task.attachments, availableFiles) { tool, success ->
                            journal.record("file_read", sessionId, task.id, action = tool, phase = if (success) "completed" else "failed")
                            status(if (success) "已读取文件资料，正在整理" else "文件读取失败，正在核对路径")
                        }
                    } catch (rejected: ModelActionRejectedException) {
                        modelClient.recordOutcome("动作未执行：${rejected.code}，${rejected.message}")
                        rejectedActions++
                        log("动作未执行 [${rejected.code}] · ${rejected.message}")
                        journal.record("invalid_decision", sessionId, task.id, phase = rejected.code)
                        if (rejectedActions >= 3) error("模型连续返回无效决策，任务已安全暂停")
                        history = (history + "动作未执行 [${rejected.code}]：${rejected.message}。根据最新屏幕重新决定。").takeLast(8)
                        continue
                    }
                    rejectedActions = 0
                    ensureActive()
                    journal.record("decision", sessionId, task.id, phase = when (decision) { is AgentDecision.Act -> "act"; is AgentDecision.AskUser -> "ask_user"; is AgentDecision.Complete -> "complete"; is AgentDecision.FileRead -> "file" })
                    when (decision) {
                        is AgentDecision.FileRead -> error("文件请求应在模型工具循环内执行")
                        is AgentDecision.AskUser -> {
                            modelClient.recordOutcome("未执行手机动作，正在等待用户回答。")
                            val question = UserQuestion(taskId = task.id, prompt = decision.prompt,
                                reason = decision.reason, options = decision.options)
                            updateSession(sessionId) { it.copy(status = SessionStatus.NEEDS_USER, question = question,
                            messages = it.conversationMessages() + ConversationMessage(role = "assistant", text = question.prompt, taskId = task.id),
                                tasks = it.tasks.map { item -> if (item.id == task.id) item.copy(status = TaskStatus.NEEDS_USER) else item }) }
                            status("${task.title} · 等待你的答复")
                            journal.record("model_question", sessionId, task.id, phase = "needs_user")
                            log("任务遇到不确定项，已暂停操作并等待答复")
                            return@launchOperation
                        }
                        is AgentDecision.Complete -> {
                            if (CopiedLinks.requested(task.title) && state.value.sessions.first { it.id == sessionId }.tasks.first { it.id == task.id }.copiedLink.isBlank())
                                error("还没有核对本次复制的链接，不能确认任务完成")
                            modelClient.recordOutcome("当前任务结束。")
                            updateSession(sessionId) { it.completeTask(task.id, decision.result) }
                            taskComplete = true
                            history = (history + "任务结果：${decision.result}").takeLast(6)
                        }
                        is AgentDecision.Act -> {
                            val requested = decision.action
                            val action = try {
                                if (observed == null) requested else progressGuard.choose(requested, observed, activeService.isLauncher(observed.packageName))
                            } catch (blocked: InputActionUnavailableException) {
                                modelClient.recordOutcome("动作未执行：${blocked.message}")
                                history = (history + "动作未执行：${blocked.message}").takeLast(8)
                                if (++inputFailures >= 3) error("翻页未能推进，已暂停；请使用应用搜索或补充信息")
                                continue
                            }
                            if (action.kind == "finish") {
                                if (ClarificationGuard.needsReply(action.reason)) {
                                    requestRecovery(sessionId, action.reason); return@launchOperation
                                }
                                if (CopiedLinks.requested(task.title) && state.value.sessions.first { it.id == sessionId }.tasks.first { it.id == task.id }.copiedLink.isBlank())
                                    error("还没有核对本次复制的链接，不能确认任务完成")
                                modelClient.recordOutcome("当前任务结束。")
                                updateSession(sessionId) { it.completeTask(task.id, action.reason.ifBlank { "这件事已经处理好了。" }) }
                                taskComplete = true
                                history = (history + "任务结果：${action.reason}").takeLast(6)
                            } else {
                                val position = when (action.kind) {
                                    "tap_element" -> " ${action.targetId}@${action.screenId}"
                                    "tap", "long_press" -> " (${(action.x * 1000).toInt()},${(action.y * 1000).toInt()})"
                                    else -> ""
                                }
                                val description = "${action.kind}$position · ${action.reason}"
                                log("模型动作 · ${action.kind}$position")
                                val copyTap = if (CopiedLinks.requested(task.title) && CopiedLinks.isCopyTap(action, observed)) System.currentTimeMillis() else 0L
                                updateSession(sessionId) { s -> s.copy(tasks = s.tasks.map { t -> if (t.id == task.id) t.checkpoint(action, observed, "prepared").let { if (copyTap > 0) it.copy(copiedLink = "", copyAttemptAt = 0) else it } else t }) }
                                journal.record("action", sessionId, task.id, action.kind, "prepared", observed?.packageName.orEmpty())
                                val result = try {
                                    val link = if (action.kind == "read_copied_link") {
                                        check(CopiedLinks.requested(task.title)) { "只有明确要求复制或分享链接的任务才能核对剪贴板" }
                                        val currentTask = state.value.sessions.first { it.id == sessionId }.tasks.first { it.id == task.id }
                                        if (currentTask.copyAttemptAt <= 0) throw InputActionUnavailableException("本任务还没有实际点击复制链接或分享链接。先在当前分享面板点击该按钮，再调用 read_copied_link；旧页面提示不算本次复制。")
                                        val verified = try { CopiedLinks.read(appContext, maxOf(currentTask.startedAt, currentTask.copyAttemptAt)) }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (_: Exception) { throw InputActionUnavailableException("没有读到本次点击后复制的新链接。请重新点击分享面板中的复制链接/分享链接，再核对；不能重复读取旧剪贴板。") }
                                        verified.also { url ->
                                            updateSession(sessionId) { s -> s.copy(tasks = s.tasks.map { t -> if (t.id == task.id) t.copy(copiedLink = url) else t }) }
                                        }
                                    } else { activeService.execute(action, frame); "" }
                                    updateSession(sessionId) { s -> s.copy(tasks = s.tasks.map { t -> if (t.id == task.id) t.checkpoint(action, observed, "submitted").let { if (copyTap > 0) it.copy(copyAttemptAt = copyTap) else it } else t }) }
                                    journal.record("action", sessionId, task.id, action.kind, "submitted", observed?.packageName.orEmpty())
                                    if (observed != null) progressGuard.submitted(action, observed)
                                    if (action.kind != "wait") inputFailures = 0
                                    if (action.kind in setOf("tap", "tap_element", "long_press")) lastTapObservation = observed else unchangedClicks = 0
                                    if (link.isNotBlank()) "已核对本次任务刚复制的链接：$link。返回给用户，不要编造或修改。"
                                    else if (action.kind == "wait") "已实际等待 ${action.duration} 毫秒，等待动作已结束。若目标仅要求等待该时长，现在应完成，不能因画面没变化再次等待。"
                                    else if (action.kind == "type_text") "已填写并读回一致；这不代表已发送。下一帧核对目标输入框和内容。"
                                    else "动作已提交；应使用下一帧重新验证业务结果，不能仅凭提交就声称成功。"
                                } catch (unavailable: InputActionUnavailableException) {
                                    updateSession(sessionId) { s -> s.copy(tasks = s.tasks.map { t -> if (t.id == task.id) t.checkpoint(action, observed, "rejected") else t }) }
                                    journal.record("action", sessionId, task.id, action.kind, "rejected", observed?.packageName.orEmpty())
                                    journal.record("action_rejected", sessionId, task.id, action.kind, FailureCodes.of(unavailable.message.orEmpty()), observed?.packageName.orEmpty())
                                    inputFailures++
                                    if (inputFailures >= 3) {
                                        requestRecovery(sessionId, "多次无法执行这一步。最近一次未执行的原因是：${unavailable.message.orEmpty()}")
                                        return@launchOperation
                                    }
                                    "动作未执行：${unavailable.message}。请根据当前画面换用其他方式。"
                                }
                                history = (history + "$description\n$result").takeLast(6)
                                modelClient.recordOutcome("动作：${action.kind}。$result")
                                delay(900)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun persistSessions(sessions: List<TaskSession>) {
        sessionRepository.save(sessions)
        mutableState.update { it.copy(sessions = sessions) }
        trackPendingQuestion()
    }

    private fun updateSession(id: String, transform: (TaskSession) -> TaskSession) {
        val before = mutableState.value.sessions.firstOrNull { it.id == id }
        val next = mutableState.value.sessions.map { session -> if (session.id == id) transform(session).copy(updatedAt = System.currentTimeMillis()) else session }
        persistSessions(next)
        // 状态跃迁只有这里能观测到，所以完成提醒挂在这一个点上，任何一条跑完的路径都覆盖得到。
        val after = next.firstOrNull { it.id == id }
        if (before != null && after != null && CompletionNoticeRules.justFinished(before.status, after.status)) noticeCompleted(after)
    }

    private fun noticeCompleted(session: TaskSession) {
        if (!CompletionNoticeRules.shouldPost(notificationsOn, foreground, state.value.selectedSessionId, session.id)) {
            journal.record("completion_notification", session.id, phase = if (notificationsOn) "skipped_foreground" else "disabled")
            return
        }
        val result = notifications.showCompleted(session.id, session.title, session.mode)
        journal.record("completion_notification", session.id, phase = result)
    }

    fun saveSettings(settings: ModelSettings): Boolean = try {
        settingsStore.save(settings)
        status("模型配置已保存在手机")
        log("模型配置已保存在手机")
        true
    } catch (error: Exception) {
        status("配置保存失败，请重试")
        false
    }

    fun attach(connectedService: PhoneAccessibilityService) {
        service = connectedService
        mutableState.update { it.copy(connected = true, status = "操作服务已连接") }
        log("操作服务已连接")
        journal.record("service_connected")
    }

    fun detach(disconnectedService: PhoneAccessibilityService) {
        if (service !== disconnectedService) return
        service = null
        mutableState.update { it.copy(connected = false) }
        interruptPhoneOperation("操作服务断开，重新连接后再核对当前页面。")
        state.value.pendingPhoneQuestion()?.let {
            questionWindowStatus(it, "操作服务未连接，待答问题已保留", "overlay_service_unavailable")
            postQuestionReminder(it, urgent = true)
        }
        journal.record("service_disconnected")
    }

    internal fun interruptPhoneOperation(reason: String) {
        val active = state.value.sessions.firstOrNull { it.id == activeSessionId && it.status == SessionStatus.RUNNING }
        if (active?.mode == ConversationMode.CHAT) return
        val task = active?.tasks?.firstOrNull { it.status == TaskStatus.RUNNING }
        stop(reason)
        if (active != null && task != null) requestRecovery(active.id, reason, task.id)
    }
    fun testModel(settings: ModelSettings) = launchOperation("正在测试图片请求") {
        settings.validate()
        modelClient.test(settings)
        status("模型图片请求已通过")
        log("模型接口成功接收图片并返回回复")
    }

    fun captureScreen() = launchOperation("正在读取屏幕") {
        capture()
        status("截图已更新")
    }

    fun manualAction(action: AgentAction) = launchOperation("2 秒后执行，请切换到练习界面") {
        val activeService = requireService()
        activeService.showStopControl()
        delay(2000)
        activeService.execute(action)
        log("基础操作已执行 · ${action.kind}")
        delay(800)
        capture()
        status("基础操作已完成")
    }

    fun start(goal: String, settings: ModelSettings, fromHome: Boolean = true) {
        if (state.value.running || state.value.sessions.any { it.question != null }) return
        val session = TaskSession(title = goal.take(40), goal = goal,
            tasks = listOf(SessionTask(title = goal, completion = goal)))
        persistSessions(state.value.sessions + session)
        selectSession(session.id)
        runSession(session.id, settings, fromHome)
    }

    fun stop(message: String = "任务已停止") {
        journal.record("user_or_service_pause", activeSessionId.orEmpty())
        generation += 1
        answerResume?.cancel()
        answerResume = null
        operation?.cancel()
        operation = null
        pauseSessions()
        service?.hideStopControl()
        mutableState.update { it.copy(running = false, status = message, streamingText = "", streamingSessionId = null) }
        log(message)
    }

    private fun pauseSessions(failure: String? = null) {
        val sessions = mutableState.value.sessions
        if (sessions.none { it.status == SessionStatus.RUNNING }) return
        persistSessions(sessions.map { session -> if (session.status == SessionStatus.RUNNING)
            session.copy(status = if (failure == null) SessionStatus.PAUSED else SessionStatus.FAILED, notice = failure ?: "已暂停，点“继续”接着做。",
                tasks = session.tasks.map { task -> if (task.status == TaskStatus.RUNNING)
                    task.copy(status = TaskStatus.PAUSED, result = failure.orEmpty()) else task }) else session })
    }

    fun clearLogs() { mutableState.update { it.copy(logs = emptyList()) } }

    fun clearScreenshot() { mutableState.update { it.copy(screenshot = null) } }

    private fun launchOperation(title: String, allowPendingQuestion: Boolean = false, block: suspend CoroutineScope.() -> Unit) {
        if (mutableState.value.running || !allowPendingQuestion && mutableState.value.sessions.any { it.question != null }) return
        val token = ++generation
        mutableState.update { it.copy(running = true, status = title, step = 0) }
        operation = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is JSONException -> "模型回复格式有误，请检查模型是否支持按 JSON 返回动作"
                    else -> error.message ?: "执行出现异常，请重试"
                }
                status(message)
                log(message)
                val active = state.value.sessions.firstOrNull { it.id == activeSessionId && it.mode == ConversationMode.PHONE && it.status == SessionStatus.RUNNING }
                if (active != null) requestRecovery(active.id, message) else pauseSessions(message)
                journal.record("operation_error", activeSessionId.orEmpty(), phase = FailureCodes.of(message))
            } finally {
                if (generation == token) {
                    service?.hideStopControl()
                    mutableState.update { it.copy(running = false, streamingText = "", streamingSessionId = null) }
                    operation = null
                    activeSessionId = null
                }
            }
        }
    }

    private fun requireService() = checkNotNull(service) { "请在系统设置中开启Vela 操作服务" }

    private suspend fun capture(): ScreenFrame {
        val frame = requireService().capture()
        mutableState.update { it.copy(screenshot = frame.bitmap) }
        return frame
    }

    private fun status(message: String) { mutableState.update { it.copy(status = message) } }

    fun dump(writer: java.io.PrintWriter) {
        val current = state.value
        writer.println("connected=${current.connected} running=${current.running} step=${current.step}")
        writer.println("status=${current.status}")
        writer.println("modelReply=${modelClient.lastReplyDiagnostic}")
        writer.println("persistentLogBytes=${journal.size()} logError=${journal.error ?: "none"}")
        current.logs.forEach { writer.println(it) }
    }

    internal fun log(message: String) {
        Log.i("PhoneAgent", message)
        when {
            message.startsWith("文字填写已验证") -> journal.record("input_verified", activeSessionId.orEmpty())
            message.startsWith("目标点击已复核") -> journal.record("target_verified", activeSessionId.orEmpty())
        }
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        mutableState.update { it.copy(logs = (it.logs + "$timestamp  $message").takeLast(80)) }
    }
}

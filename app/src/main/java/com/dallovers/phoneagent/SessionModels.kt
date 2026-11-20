package com.dallovers.phoneagent

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class ConversationMode { CHAT, PHONE }

enum class SessionStatus { READY, RUNNING, NEEDS_USER, PAUSED, COMPLETED, FAILED }
enum class TaskStatus { TODO, RUNNING, NEEDS_USER, PAUSED, COMPLETED, FAILED, SKIPPED }

data class ConversationMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val taskId: String = "",
)

data class SessionTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val completion: String = "",
    val status: TaskStatus = TaskStatus.TODO,
    val result: String = "",
    val attachments: List<TextAttachment> = emptyList(),
    val progress: List<TaskStep> = emptyList(),
    val stepsTaken: Int = 0,
    val startedAt: Long = 0,
    val copiedLink: String = "",
    val lastIssue: String = "",
    val copyAttemptAt: Long = 0,
)

data class UserQuestion(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val prompt: String,
    val reason: String,
    val options: List<String> = emptyList(),
)

data class TaskSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val goal: String,
    val constraints: String = "",
    val summary: String = "",
    val tasks: List<SessionTask>,
    val status: SessionStatus = SessionStatus.READY,
    val question: UserQuestion? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ConversationMessage> = emptyList(),
    val notice: String = "",
    val mode: ConversationMode = ConversationMode.PHONE,
    val archived: Boolean = false,
    val folder: String = "",
    val pinned: Boolean = false,
    val workspaceId: String = WorkspacePaths.DEFAULT,
    val savedFiles: List<SavedReplyFile> = emptyList(),
    val fileChatDirectory: String? = null,
)

class SessionRepository(context: Context) {
    private val file = File(context.filesDir, "task_sessions.json")

    var loadError: String? = null
        private set

    @Synchronized fun load(): List<TaskSession> = try {
        val rows = JSONArray(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        (0 until rows.length()).map { SessionCodec.decode(rows.getJSONObject(it)).safeAfterRestart() }
    } catch (_: java.io.FileNotFoundException) { emptyList() }
      catch (_: Exception) { loadError = "会话数据无法读取，已保留原文件并禁用写入"; emptyList() }

    @Synchronized fun save(sessions: List<TaskSession>) {
        check(loadError == null) { loadError.orEmpty() }
        file.parentFile?.mkdirs()
        val rows = JSONArray()
        sessions.forEach { rows.put(SessionCodec.encode(it)) }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(rows.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

}

object SessionCodec {
    fun encode(session: TaskSession) = JSONObject().put("schema", 8)
        .put("id", session.id).put("title", session.title).put("goal", session.goal)
        .put("constraints", session.constraints).put("summary", session.summary)
        .put("status", session.status.name).put("updatedAt", session.updatedAt)
        .put("notice", session.notice).put("mode", session.mode.name).put("archived", session.archived).put("folder", session.folder).put("pinned", session.pinned).put("workspaceId", session.workspaceId)
        .apply { session.fileChatDirectory?.let { put("fileChatDirectory", it) } }
        .put("savedFiles", JSONArray().apply { session.savedFiles.forEach { put(JSONObject().put("name", it.name).put("sha256", it.sha256).put("messageId", it.messageId)) } })
        .put("messages", JSONArray().apply { session.messages.forEach { message -> put(JSONObject()
            .put("id", message.id).put("role", message.role).put("text", message.text).put("taskId", message.taskId)) } })
        .put("question", session.question?.let { question -> JSONObject().put("id", question.id)
            .put("taskId", question.taskId).put("prompt", question.prompt).put("reason", question.reason)
            .put("options", JSONArray(question.options)) })
        .put("tasks", JSONArray().apply { session.tasks.forEach { task -> put(JSONObject()
            .put("id", task.id).put("title", task.title).put("completion", task.completion)
            .put("status", task.status.name).put("result", task.result).put("attachments", AttachmentCodec.encode(task.attachments))
            .put("stepsTaken", task.stepsTaken).put("startedAt", task.startedAt).put("copiedLink", task.copiedLink).put("lastIssue", task.lastIssue).put("copyAttemptAt", task.copyAttemptAt).put("progress", JSONArray().apply { task.progress.forEach { step -> put(JSONObject()
                .put("sequence", step.sequence).put("action", step.action).put("intent", step.intent).put("phase", step.phase)
                .put("packageName", step.packageName).put("screenId", step.screenId).put("time", step.time)) } })) } })

    fun decode(row: JSONObject): TaskSession {
        require(row.optInt("schema") in 1..8) { "不支持的会话数据版本" }
        val tasks = row.getJSONArray("tasks")
        val questionJson = row.optJSONObject("question")
        return TaskSession(id = row.getString("id"), title = row.getString("title"), goal = row.getString("goal"),
            constraints = row.optString("constraints"), summary = row.optString("summary"),
            status = runCatching { SessionStatus.valueOf(row.optString("status")) }.getOrDefault(SessionStatus.PAUSED),
            updatedAt = row.optLong("updatedAt"),
            notice = row.optString("notice"),
            mode = runCatching { ConversationMode.valueOf(row.optString("mode")) }.getOrDefault(ConversationMode.PHONE),
            archived = row.optBoolean("archived"),
            folder = row.optString("folder").take(30), pinned = row.optBoolean("pinned"),
            workspaceId = row.optString("workspaceId", WorkspacePaths.DEFAULT).also(WorkspacePaths::validateId),
            fileChatDirectory = (row.opt("fileChatDirectory") as? String)?.also { if (it.isNotEmpty()) validateRelativePath(it) },
            savedFiles = row.optJSONArray("savedFiles")?.let { files -> (0 until files.length()).map { i -> files.getJSONObject(i).let {
                SavedReplyFile(it.getString("name"), it.getString("sha256"), it.getString("messageId"))
            } } }.orEmpty(),
            messages = row.optJSONArray("messages")?.let { messages -> (0 until messages.length()).map { i ->
                val message = messages.getJSONObject(i)
                ConversationMessage(message.getString("id"), message.getString("role"), message.getString("text"), message.optString("taskId"))
            } }.orEmpty(),
            question = questionJson?.let { q -> val options = q.optJSONArray("options") ?: JSONArray()
                UserQuestion(q.getString("id"), q.getString("taskId"), q.getString("prompt"), q.getString("reason"),
                    (0 until options.length()).map(options::getString)) },
            tasks = (0 until tasks.length()).map { index -> tasks.getJSONObject(index).let { task -> SessionTask(
                id = task.getString("id"), title = task.getString("title"), completion = task.optString("completion"),
                status = runCatching { TaskStatus.valueOf(task.optString("status")) }.getOrDefault(TaskStatus.PAUSED),
                result = task.optString("result"), attachments = AttachmentCodec.decode(task.optJSONArray("attachments") ?: JSONArray()),
                stepsTaken = task.optInt("stepsTaken"), startedAt = task.optLong("startedAt"), copiedLink = task.optString("copiedLink"), lastIssue = task.optString("lastIssue").take(500), copyAttemptAt = task.optLong("copyAttemptAt"), progress = task.optJSONArray("progress")?.let { rows -> (0 until rows.length()).toList().takeLast(120).map { i ->
                    rows.getJSONObject(i).let { TaskStep(it.getInt("sequence"), it.getString("action"), it.optString("intent").take(240),
                        it.getString("phase"), it.optString("packageName"), it.optString("screenId"), it.optLong("time")) }
                } }.orEmpty()) } }).also { session ->
            session.tasks.forEach { TextAttachment.validateBatch(it.attachments, session.workspaceId) }
        }
    }
}

fun parseTaskPlan(text: String): List<SessionTask> = text.lineSequence().map { it.trim() }
    .map { it.removePrefix("- ").removePrefix("• ").replace(Regex("^\\d+[.)、]\\s*"), "") }
    .filter { it.isNotBlank() }.take(12).map { SessionTask(title = it, completion = it) }.toList()

fun TaskSession.safeAfterRestart(): TaskSession = if (status == SessionStatus.RUNNING || tasks.any { it.status == TaskStatus.RUNNING })
    copy(status = if (question != null) SessionStatus.NEEDS_USER else SessionStatus.PAUSED,
        notice = "任务被中断，进度已保留。点继续后先核对当前页面，不会自动从头开始。",
        tasks = tasks.map { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.PAUSED) else it }) else this

fun TaskSession.withAnswer(questionId: String, answer: String): TaskSession {
    val pending = question ?: return this
    if (status != SessionStatus.NEEDS_USER || pending.id != questionId || answer.isBlank() || answer.length > 4000) return this
    val task = tasks.firstOrNull { it.id == pending.taskId && it.status == TaskStatus.NEEDS_USER } ?: return this
    return copy(status = SessionStatus.PAUSED, question = null,
        messages = conversationMessages() + ConversationMessage(role = "user", text = answer.trim().take(4000), taskId = task.id),
        summary = listOf(summary, "用户答复（${task.title.take(60)}）：${answer.trim().take(4000)}").filter(String::isNotBlank).joinToString("\n").takeLast(6000),
        tasks = tasks.map { if (it.id == task.id) it.copy(status = TaskStatus.PAUSED) else it })
}

fun TaskSession.conversationMessages(): List<ConversationMessage> = messages.ifEmpty {
    if (goal.isBlank() && tasks.isEmpty()) emptyList() else
    listOf(ConversationMessage(id = "legacy-$id", role = "user", text = goal)) +
        tasks.filter { it.status == TaskStatus.COMPLETED && it.result.isNotBlank() }.map {
            ConversationMessage(id = "legacy-${it.id}", role = "assistant", text = it.result, taskId = it.id)
        }
}

/** A message is one request, including its line breaks. Running tasks are never rewritten. */
fun TaskSession.queueMessage(text: String, attachments: List<TextAttachment> = emptyList()): TaskSession {
    require(text.isNotBlank() && text.length <= 4000)
    TextAttachment.validateBatch(attachments, workspaceId)
    val task = SessionTask(title = text.trim(), completion = text.trim(), attachments = attachments)
    return copy(tasks = tasks + task, notice = "", status = if (status == SessionStatus.COMPLETED) SessionStatus.READY else status,
        messages = conversationMessages() + ConversationMessage(role = "user", text = task.title, taskId = task.id))
}

fun TaskSession.completeTask(taskId: String, result: String): TaskSession {
    val link = tasks.firstOrNull { it.id == taskId }?.copiedLink.orEmpty()
    val visibleResult = if (link.isNotBlank() && link !in result) "$result\n\n$link" else result
    return copy(tasks = tasks.map { if (it.id == taskId) it.copy(status = TaskStatus.COMPLETED, result = visibleResult) else it },
        messages = conversationMessages() + ConversationMessage(role = "assistant", text = visibleResult, taskId = taskId))
}

/** Explicit user steering retires one active request; it never silently rewrites completed work. */
fun TaskSession.replaceCurrentRequest(text: String, attachments: List<TextAttachment> = emptyList()): TaskSession {
    require(text.isNotBlank() && text.length <= 4000)
    TextAttachment.validateBatch(attachments, workspaceId)
    val newTask = SessionTask(title = text.trim(), completion = text.trim(), attachments = attachments)
    val active = tasks.indexOfFirst { it.status in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.TODO) }
    val next = tasks.toMutableList()
    if (active >= 0) {
        next[active] = next[active].copy(status = TaskStatus.SKIPPED, result = "用户已修改要求，原任务停止。已执行的操作不会自动撤销。")
        next.add(active + 1, newTask)
    } else next += newTask
    return copy(tasks = next, status = SessionStatus.READY, question = null, notice = "按你的新要求继续。",
        messages = conversationMessages() + ConversationMessage(role = "user", text = newTask.title, taskId = newTask.id))
}

/** Exports visible conversation only, never model credentials, screenshots or hidden reasoning. */
fun TaskSession.exportMarkdown(): String = buildString {
    append("# ").append(title.replace('\n', ' ')).append("\n\n")
    append(if (mode == ConversationMode.CHAT) "聊天" else "手机助手").append("\n\n")
    conversationMessages().forEach {
        append("## ").append(if (it.role == "user") "我" else "助手").append("\n\n")
        append(it.text).append("\n\n")
        if (it.role == "user") tasks.firstOrNull { task -> task.id == it.taskId }?.attachments?.forEach { file ->
            append("附件：").append(file.name).append(" · SHA-256 ").append(file.sha256).append("\n\n")
        }
    }
}

/** Branches are independent snapshots: no future messages, memory or pending phone actions. */
fun TaskSession.chatBranch(messageId: String, retry: Boolean = false): TaskSession {
    require(mode == ConversationMode.CHAT) { "只有聊天回复可以创建分支或重新回答" }
    val transcript = conversationMessages()
    val index = transcript.indexOfFirst { it.id == messageId && it.role == "assistant" }
    require(index >= 0) { "没有找到这条回复" }
    val picked = transcript[index]
    val included = transcript.take(if (retry) index else index + 1)
    val completedIds = included.filter { it.role == "assistant" }.map { it.taskId }.toSet()
    val selectedTasks = tasks.filter { it.id in completedIds || retry && it.id == picked.taskId }.map {
        if (retry && it.id == picked.taskId) it.copy(status = TaskStatus.TODO, result = "")
        else it.copy(status = TaskStatus.COMPLETED)
    }
    require(selectedTasks.isNotEmpty() && (!retry || included.any { it.role == "user" && it.taskId == picked.taskId })) { "这条旧回复缺少完整的对话记录" }
    return TaskSession(title = title.take(32) + if (retry) " · 重新回答" else " · 分支",
        goal = selectedTasks.first().title, tasks = selectedTasks, messages = included,
        mode = ConversationMode.CHAT, folder = folder, workspaceId = workspaceId, fileChatDirectory = fileChatDirectory,
        status = if (retry) SessionStatus.READY else SessionStatus.COMPLETED)
}

fun TaskSession.canChangeMode(): Boolean = status != SessionStatus.RUNNING && question == null &&
    tasks.all { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.SKIPPED) }

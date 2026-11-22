package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 证据包内的一个条目。全部是纯文本，既能直接读，也能用 diff 比对两次导出。 */
internal data class EvidenceEntry(val name: String, val text: String) {
    fun bytes(): ByteArray = text.toByteArray(Charsets.UTF_8)
    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(bytes()).joinToString("") { "%02x".format(it) }
}

internal data class JournalSlice(val lines: List<String>, val matched: Int)

/** 打包计划：最终条目，以及因为上限被丢掉 / 被截断的条目名。 */
internal data class BundlePlan(
    val entries: List<EvidenceEntry>,
    val dropped: List<String>,
    val truncated: List<String>,
)

/**
 * 一次导出的成品。[omitted] 大于 0 时，界面必须明确告诉用户「少了东西」，
 * 不能因为导出成功就让用户以为包是完整的。
 */
internal class BundleExport(
    val bytes: ByteArray,
    val dropped: List<String>,
    val truncated: List<String>,
) {
    val omitted: Int = dropped.size + truncated.size
}

/**
 * 把一个对话导出成可复查的证据包：清单、可见对话、任务结构、执行轨迹、本次对话的运行日志。
 * 刻意不含模型密钥、接口地址、截图与图片内容、输入框正文、模型思考过程，
 * 也不复制附件原文（只记文件名与 SHA-256，原文还在工作区里）。
 *
 * 所有上限都是硬上限，并且一定会写进 manifest 与 README：宁可少写，也不静默丢内容。
 * 为了真能说清「少了什么」，README 自己不参与预算淘汰——它被丢掉恰恰是最需要它的时候。
 */
internal object EvidenceBundle {
    const val SCHEMA = 1
    const val MAX_ENTRY_BYTES = 256 * 1024
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    const val MAX_JOURNAL_LINES = 800
    const val MAX_TRACE_STEPS = 200
    private const val MANIFEST_RESERVE = 64 * 1024
    internal const val CUT = "…（已截断）"

    fun fileName(title: String, sessionId: String, now: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "证据包-${slug(title, sessionId)}-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply { timeZone = zone }.format(Date(now))}.zip"

    /** 文件名里不允许的字符统一换成短横线，避免把标题里的路径分隔符带进文件名。 */
    fun slug(title: String, sessionId: String): String = title
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-")
        .trim('-', '.', ' ', '\t')
        .take(20)
        .ifBlank { sessionId.take(8) }

    fun stamp(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }.format(Date(millis))

    fun build(
        session: TaskSession,
        journalRaw: String,
        model: String,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        totalBudgetBytes: Int = MAX_TOTAL_BYTES,
    ): List<EvidenceEntry> = plan(session, journalRaw, model, now, zone, totalBudgetBytes).entries

    /** 打包并回传省略情况，给界面用；[build] 只要条目时仍可直接用。 */
    fun export(
        session: TaskSession,
        journalRaw: String,
        model: String,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        totalBudgetBytes: Int = MAX_TOTAL_BYTES,
    ): BundleExport {
        val plan = plan(session, journalRaw, model, now, zone, totalBudgetBytes)
        return BundleExport(zip(plan.entries), plan.dropped, plan.truncated)
    }

    private fun plan(
        session: TaskSession,
        journalRaw: String,
        model: String,
        now: Long,
        zone: TimeZone,
        totalBudgetBytes: Int,
    ): BundlePlan {
        val rendered = mutableListOf<Pair<EvidenceEntry, Boolean>>()
        fun add(name: String, text: String, cut: Boolean = false) {
            val clipped = clip(name, text)
            rendered += clipped.first to (clipped.second || cut)
        }
        add("conversation.md", session.exportMarkdown())
        add("tasks.json", tasksJson(session, zone))
        session.tasks.forEachIndexed { index, task ->
            add("trace/${index + 1}-${slug(task.title, task.id)}.csv", traceCsv(task, zone),
                cut = task.progress.size > MAX_TRACE_STEPS)
        }
        val slice = journalSlice(journalRaw, session.id)
        add("journal.jsonl", slice.lines.joinToString("\n"), cut = slice.matched > slice.lines.size)

        val budget = totalBudgetBytes - MANIFEST_RESERVE
        val kept = mutableListOf<EvidenceEntry>()
        val dropped = mutableListOf<String>()
        var used = 0
        rendered.forEach { (entry, _) ->
            val size = entry.bytes().size
            if (used + size <= budget) {
                kept += entry
                used += size
            } else dropped += entry.name
        }
        val truncated = rendered.filter { it.second }.map { it.first.name }
        // README 要写「哪些被省略了」，所以先算出 dropped 再生成；它自己永远进包。
        kept += EvidenceEntry("README.txt", readme(session, now, zone, dropped, truncated))
        val manifest = manifest(session, model, now, zone, kept, truncated, dropped)
        return BundlePlan(listOf(EvidenceEntry("manifest.json", manifest)) + kept, dropped, truncated)
    }

    fun clip(name: String, text: String): Pair<EvidenceEntry, Boolean> {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size <= MAX_ENTRY_BYTES) return EvidenceEntry(name, text) to false
        var end = MAX_ENTRY_BYTES
        while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
        return EvidenceEntry(name, String(raw, 0, end, Charsets.UTF_8) + "\n" + CUT) to true
    }

    fun journalSlice(raw: String, sessionId: String, limit: Int = MAX_JOURNAL_LINES): JournalSlice {
        val matched = raw.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
            val row = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            if (row.optString("session") == sessionId) line else null
        }.toList()
        return JournalSlice(matched.takeLast(limit), matched.size)
    }

    fun traceCsv(task: SessionTask, zone: TimeZone = TimeZone.getDefault()): String = buildString {
        append("sequence,action,intent,phase,package,screen,time\r\n")
        task.progress.takeLast(MAX_TRACE_STEPS).forEach { step ->
            append(step.sequence).append(',').append(cell(step.action)).append(',')
                .append(cell(step.intent)).append(',').append(cell(step.phase)).append(',')
                .append(cell(step.packageName)).append(',').append(cell(step.screenId)).append(',')
                .append(cell(stamp(step.time, zone))).append("\r\n")
        }
    }

    /** RFC 4180：只有含分隔符、引号或换行的字段才加引号，引号自身翻倍。 */
    fun cell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
        "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun tasksJson(session: TaskSession, zone: TimeZone = TimeZone.getDefault()): String {
        val tasks = JSONArray()
        session.tasks.forEach { task ->
            val attachments = JSONArray()
            task.attachments.forEach { file ->
                attachments.put(JSONObject().put("name", file.name).put("sha256", file.sha256)
                    .put("bytes", file.text.toByteArray(Charsets.UTF_8).size)
                    .put("pages", file.pages.size).put("pageCount", file.pageCount)
                    .put("kind", if (file.document.isNotEmpty()) "document" else "text"))
            }
            tasks.put(JSONObject().put("id", task.id).put("title", task.title).put("completion", task.completion)
                .put("status", task.status.name).put("result", task.result)
                .put("stepsTaken", task.stepsTaken).put("progressSteps", task.progress.size)
                .put("startedAt", task.startedAt).put("startedAtText", if (task.startedAt > 0) stamp(task.startedAt, zone) else "")
                .put("copiedLink", task.copiedLink).put("lastIssue", task.lastIssue)
                .put("attachments", attachments))
        }
        return JSONObject().put("schema", SCHEMA)
            .put("session", JSONObject().put("id", session.id).put("title", session.title).put("goal", session.goal)
                .put("constraints", session.constraints).put("summary", session.summary)
                .put("status", session.status.name).put("mode", session.mode.name)
                .put("workspaceId", session.workspaceId).put("folder", session.folder)
                .put("archived", session.archived).put("updatedAt", session.updatedAt)
                .put("updatedAtText", stamp(session.updatedAt, zone))
                .put("messages", session.conversationMessages().size))
            .put("tasks", tasks).put("attachmentsCopied", false).toString(2)
    }

    fun manifest(
        session: TaskSession,
        model: String,
        now: Long,
        zone: TimeZone,
        entries: List<EvidenceEntry>,
        truncated: List<String>,
        dropped: List<String>,
    ): String {
        val files = JSONArray()
        entries.forEach { files.put(JSONObject().put("name", it.name).put("bytes", it.bytes().size).put("sha256", it.sha256())) }
        return JSONObject().put("schema", SCHEMA).put("tool", "Vela")
            .put("generatedAt", stamp(now, zone)).put("generatedAtMillis", now)
            .put("model", model)
            .put("session", JSONObject().put("id", session.id).put("title", session.title)
                .put("status", session.status.name).put("workspaceId", session.workspaceId))
            .put("counts", JSONObject().put("tasks", session.tasks.size)
                .put("messages", session.conversationMessages().size)
                .put("files", entries.size + 1))
            .put("limits", JSONObject().put("entryBytes", MAX_ENTRY_BYTES).put("totalBytes", MAX_TOTAL_BYTES)
                .put("journalLines", MAX_JOURNAL_LINES).put("traceSteps", MAX_TRACE_STEPS))
            .put("files", files)
            .put("truncated", JSONArray(truncated))
            .put("dropped", JSONArray(dropped))
            .put("excluded", JSONArray(listOf("模型密钥", "接口地址", "截图与图片内容", "输入框正文", "模型思考过程", "附件原文")))
            .toString(2)
    }

    fun readme(
        session: TaskSession,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        dropped: List<String> = emptyList(),
        truncated: List<String> = emptyList(),
    ): String = buildString {
        append("Vela 任务证据包\n\n")
        append("对话：").append(session.title.replace('\n', ' ')).append("\n")
        append("编号：").append(session.id).append("\n")
        append("导出时间：").append(stamp(now, zone)).append("\n")
        append("格式版本：").append(SCHEMA).append("\n\n")
        if (dropped.isNotEmpty() || truncated.isNotEmpty()) {
            append("注意：这个包不完整。\n")
            if (dropped.isNotEmpty()) append("  省略 ").append(dropped.size).append(" 项：").append(dropped.joinToString("、")).append("\n")
            if (truncated.isNotEmpty()) append("  截断 ").append(truncated.size).append(" 项：").append(truncated.joinToString("、")).append("\n")
            append("  原因是总大小超过上限。需要完整内容时，先清理对话里的长消息或附件，再重新导出。\n\n")
        }
        append("包含：\n")
        append("  manifest.json   包清单：每个文件的字节数与 SHA-256\n")
        append("  conversation.md 你与助手的可见对话\n")
        append("  tasks.json      任务、状态、结果，以及附件清单（只记文件名与校验值）\n")
        append("  trace/*.csv     每个任务的执行轨迹：第几步、什么动作、在哪个页面\n")
        append("  journal.jsonl   本次对话的运行日志\n\n")
        append("不含：模型密钥、接口地址、截图与图片内容、输入框正文、模型思考过程、附件原文。\n")
        append("复查某一步是否做对：先在 trace 里找对应 sequence 的 action 与 screen，\n")
        append("再用 journal 的 time 对齐当时发生了什么。manifest 的 dropped / truncated 说明哪些内容被上限截掉。\n")
    }

    fun zip(entries: List<EvidenceEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                zip.write(entry.bytes())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

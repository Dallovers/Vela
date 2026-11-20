package com.dallovers.phoneagent

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Device-private, bounded diagnostic trail. Never accepts screen text, typed text or credentials. */
class RunJournal(private val directory: File, private val maxBytes: Long = 512 * 1024, private val maxFiles: Int = 4) {
    var error: String? = null
        private set
    @Synchronized fun record(event: String, sessionId: String = "", taskId: String = "", action: String = "", phase: String = "", packageName: String = "", count: Int = 0) {
        runCatching {
            require(Regex("[a-z_]{1,40}").matches(event))
            directory.mkdirs()
            val file = File(directory, "current.jsonl")
            val firstTime = if (file.exists()) runCatching { file.bufferedReader().use { JSONObject(it.readLine()).getLong("time") } }.getOrDefault(System.currentTimeMillis()) else System.currentTimeMillis()
            if (file.length() >= maxBytes || System.currentTimeMillis() - firstTime >= 86400000L) {
                File(directory, "run-${System.currentTimeMillis()}-${System.nanoTime()}.jsonl").also { check(file.renameTo(it)) }
            }
            val row = JSONObject().put("time", System.currentTimeMillis()).put("event", event)
                .put("session", sessionId.take(80)).put("task", taskId.take(80)).put("action", action.take(40))
                .put("phase", phase.take(50)).put("package", packageName.take(140)).put("count", count)
            FileOutputStream(file, true).use { it.write((row.toString() + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val ordered = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }.sortedByDescending { it.lastModified() }
            ordered.drop(maxFiles).forEach { it.delete() }
            ordered.filter { it != file && it.lastModified() < System.currentTimeMillis() - 14L * 86400000 }.forEach { it.delete() }
            error = null
        }.onFailure { error = "运行日志写入失败，请检查手机剩余空间" }
    }
    @Synchronized fun export(): String = directory.listFiles().orEmpty().filter { it.extension == "jsonl" }
        .sortedBy { it.lastModified() }.joinToString("\n") { it.readText(Charsets.UTF_8) }
    fun size(): Long = directory.listFiles().orEmpty().sumOf { it.length() }
}

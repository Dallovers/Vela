package com.dallovers.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class WorkspaceInfo(val id: String, val name: String, internal val phone: PhoneFolder? = null)

object WorkspacePaths {
    const val DEFAULT = "local"
    fun validateId(id: String) {
        require(id == DEFAULT || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "工作区编号无效" }
    }
    fun root(filesDir: File, id: String): File {
        validateId(id)
        val base = filesDir.canonicalFile
        val candidate = if (id == DEFAULT) File(base, "workspace") else File(File(base, "workspaces"), id)
        require(candidate.canonicalFile == candidate.absoluteFile) { "工作区不能指向其他目录" }
        return candidate
    }
}

/** Pure rules for the workspace index, kept free of Android types so they can be unit tested. */
internal object WorkspaceRules {
    const val MAX_NAME = 40

    fun cleanName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= MAX_NAME && trimmed.none { it.isISOControl() }) { "工作区名称最多 40 字" }
        return trimmed
    }

    /** Returns the rows with [id] renamed, keeping the original order. */
    fun renamed(rows: List<WorkspaceInfo>, id: String, name: String): List<WorkspaceInfo> {
        require(id != WorkspacePaths.DEFAULT) { "默认工作区不能改名" }
        require(rows.any { it.id == id }) { "没有找到这个工作区" }
        val cleaned = cleanName(name)
        require(rows.none { it.id != id && it.name == cleaned }) { "已有同名工作区" }
        return rows.map { if (it.id == id) it.copy(name = cleaned) else it }
    }

    /** Returns the rows without [id]. The default workspace is never removable. */
    fun without(rows: List<WorkspaceInfo>, id: String): List<WorkspaceInfo> {
        require(id != WorkspacePaths.DEFAULT) { "默认工作区不能移除" }
        require(rows.any { it.id == id }) { "没有找到这个工作区" }
        return rows.filterNot { it.id == id }
    }
}

class WorkspaceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("workspaces", Context.MODE_PRIVATE)
    var loadError: String? = null
        private set
    fun list(): List<WorkspaceInfo> = try {
        val rows = JSONArray(prefs.getString("items", "[]"))
        val values = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            WorkspaceInfo(row.getString("id"), row.getString("name"), if (row.has("volume")) PhoneFolder(row.getString("volume"), row.getString("path")) else null).also { WorkspacePaths.validateId(it.id) }
        } }
        require(values.map { it.id }.distinct().size == values.size)
        if (values.any { it.id == WorkspacePaths.DEFAULT }) values else listOf(WorkspaceInfo(WorkspacePaths.DEFAULT, "本机工作区")) + values
    } catch (_: Exception) {
        loadError = "工作区记录无法读取，已保留原数据并禁用修改"
        listOf(WorkspaceInfo(WorkspacePaths.DEFAULT, "本机工作区"))
    }
    fun current(): String = prefs.getString("current", WorkspacePaths.DEFAULT).orEmpty().let { id ->
        if (list().any { it.id == id }) id else WorkspacePaths.DEFAULT
    }
    fun choose(id: String) {
        check(loadError == null) { loadError.orEmpty() }
        require(list().any { it.id == id }) { "没有找到这个工作区" }
        check(prefs.edit().putString("current", id).commit()) { "工作区选择未保存" }
    }
    fun create(name: String): WorkspaceInfo = add(name, null)
    fun openFolder(folder: PhoneFolder): WorkspaceInfo {
        val root = PhoneStorage(context).root(folder)
        require(phonePathAllowed(folder.path)) { "这个目录受系统保护" }
        list().firstOrNull { it.phone == folder }?.let { choose(it.id); return it }
        return add(if (folder.path.isEmpty()) "手机存储" else root.name.take(40), folder)
    }
    private fun add(name: String, phone: PhoneFolder?): WorkspaceInfo {
        val rows = list()
        check(loadError == null) { loadError.orEmpty() }
        val cleaned = WorkspaceRules.cleanName(name)
        require(rows.size < 20) { "最多创建 20 个工作区" }
        require(phone != null || rows.none { it.name == cleaned }) { "已有同名工作区" }
        val info = WorkspaceInfo(UUID.randomUUID().toString(), cleaned, phone)
        if (phone == null) WorkspaceFiles(WorkspacePaths.root(context.filesDir, info.id))
        persist(rows + info, info.id)
        return info
    }

    /** Renames a workspace. Directories are keyed by id, so no file moves. */
    fun rename(id: String, name: String) {
        check(loadError == null) { loadError.orEmpty() }
        val rows = list()
        val next = WorkspaceRules.renamed(rows, id, name)
        if (next != rows) persist(next, current())
    }

    /**
     * Drops a workspace from the index without deleting anything on disk. Returns the directory
     * that still holds its files, so the caller can tell the user where they went; a workspace
     * backed by a phone folder returns null because those files were never ours.
     */
    fun remove(id: String): File? {
        check(loadError == null) { loadError.orEmpty() }
        val rows = list()
        val info = rows.firstOrNull { it.id == id } ?: error("没有找到这个工作区")
        val next = WorkspaceRules.without(rows, id)
        persist(next, if (current() == id) WorkspacePaths.DEFAULT else current())
        if (info.phone != null) return null
        return WorkspacePaths.root(context.filesDir, id).takeIf { it.exists() }
    }

    private fun persist(rows: List<WorkspaceInfo>, currentId: String) {
        val json = JSONArray().apply {
            rows.forEach { row ->
                put(JSONObject().put("id", row.id).put("name", row.name).apply {
                    row.phone?.let { folder -> put("volume", folder.volume); put("path", folder.path) }
                })
            }
        }
        check(prefs.edit().putString("items", json.toString()).putString("current", currentId).commit()) { "工作区记录未保存" }
    }
    fun files(id: String): WorkspaceFiles {
        val info = list().singleOrNull { it.id == id } ?: error("没有找到这个工作区")
        val folder = info.phone ?: return WorkspaceFiles(WorkspacePaths.root(context.filesDir, id))
        val storage = PhoneStorage(context)
        val root = storage.root(folder)
        return WorkspaceFiles(root, folder.path) {
            check(storage.root(folder) == root) { "存储位置已变化，请重新打开" }
        }
    }
}

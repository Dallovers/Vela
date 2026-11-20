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
        require(name.isNotBlank() && name.trim().length <= 40 && name.none { it.isISOControl() }) { "工作区名称最多 40 字" }
        require(rows.size < 20) { "最多创建 20 个工作区" }
        require(phone != null || rows.none { it.name == name.trim() }) { "已有同名工作区" }
        val info = WorkspaceInfo(UUID.randomUUID().toString(), name.trim(), phone)
        if (phone == null) WorkspaceFiles(WorkspacePaths.root(context.filesDir, info.id))
        val json = JSONArray().apply { (rows + info).forEach { put(JSONObject().put("id", it.id).put("name", it.name).apply { it.phone?.let { folder -> put("volume", folder.volume); put("path", folder.path) } }) } }
        check(prefs.edit().putString("items", json.toString()).putString("current", info.id).commit()) { "工作区创建未保存" }
        return info
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

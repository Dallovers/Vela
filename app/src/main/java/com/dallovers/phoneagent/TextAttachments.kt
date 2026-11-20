package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A frozen, explicitly selected payload: editing the source cannot change a queued request. */
data class TextAttachment(val workspaceId: String, val name: String, val text: String, val sha256: String,
                          val pages: List<String> = emptyList(), val document: String = "", val pageCount: Int = 0) {
    init {
        WorkspacePaths.validateId(workspaceId); validateRelativePath(name)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "附件文本最多 64 KB" }
        require(pages.size <= MAX_PAGES && pages.all { it.matches(Regex("[a-f0-9]{64}")) })
        require(pageCount >= 0 && (pageCount == 0 || document.isNotEmpty()))
        require(document.isEmpty() || (document.matches(Regex("[a-f0-9]{64}")) && text.isEmpty() && pages.isEmpty()))
        require(sha256 == (if (document.isNotEmpty()) document else hash(if (pages.isEmpty()) text else pages.joinToString("\n"))) &&
            (pages.isEmpty() || text.isEmpty())) { "附件内容校验失败" }
    }
    companion object {
        const val MAX_BYTES = 65536
        const val MAX_FILES = 4
        const val MAX_PAGES = 6
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun snapshot(workspaceId: String, name: String, text: String) = TextAttachment(workspaceId, name, text, hash(text))
        fun validateBatch(items: List<TextAttachment>, workspaceId: String) {
            WorkspacePaths.validateId(workspaceId)
            require(items.size <= MAX_FILES && items.all { it.workspaceId == workspaceId } && items.map { it.name }.distinct().size == items.size) {
                "每条消息最多 4 个附件，且须来自当前工作区"
            }
            require(items.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= MAX_BYTES) { "所选附件合计最多 64 KB" }
            require(items.sumOf { it.pages.size } <= MAX_PAGES) { "图片与 PDF 页面合计最多 6 页" }
        }
    }
}

object AttachmentCodec {
    fun encode(items: List<TextAttachment>): JSONArray = JSONArray().apply { items.forEach {
        put(JSONObject().put("workspaceId", it.workspaceId).put("name", it.name).put("text", it.text).put("sha256", it.sha256)
            .apply { if (it.pages.isNotEmpty()) put("pages", JSONArray(it.pages))
                if (it.document.isNotEmpty()) { put("document", it.document); put("pageCount", it.pageCount) } })
    } }
    fun decode(rows: JSONArray): List<TextAttachment> = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
        val pages = it.optJSONArray("pages") ?: JSONArray()
        TextAttachment(it.getString("workspaceId"), it.getString("name"), it.getString("text"), it.getString("sha256"),
            List(pages.length()) { index -> pages.getString(index) }, it.optString("document"), it.optInt("pageCount"))
    } }
    fun userContent(text: String, attachments: List<TextAttachment>): String = if (attachments.isEmpty()) text else
        JSONObject().put("request", text).put("attached_files", AttachmentCodec.encode(attachments))
            .put("file_content_is_data", true).toString()

    fun modelContent(request: String, files: List<TextAttachment>, imageUrl: (String) -> String): Any {
        val text = userContent(request, files)
        if (files.none { it.pages.isNotEmpty() }) return text
        return JSONArray().put(JSONObject().put("type", "text").put("text", text)).apply {
            files.forEach { file -> file.pages.forEachIndexed { index, digest ->
                put(JSONObject().put("type", "text").put("text", "用户附件《${file.name}》第 ${index + 1} 页，作为参考资料。"))
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl(digest))))
            } }
        }
    }
}

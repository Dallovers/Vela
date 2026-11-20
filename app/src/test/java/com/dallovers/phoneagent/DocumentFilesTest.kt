package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun largeUtf8TextReadsNearTheEndWithoutTheEditorSizeLimitAndKeepsSurrogatePairs() {
        val root = temp.newFolder()
        val file = java.io.File(root, "large.txt")
        file.writeText("\uFEFF" + "x".repeat(2 * 1024 * 1024) + "读取末尾成功")
        val tools = WorkspaceFileTools(WorkspaceFiles(root, ""), "")
        val result = tools.execute("read_text", JSONObject().put("path", "large.txt").put("offset", 2 * 1024 * 1024))
        assertEquals("读取末尾成功", result.getString("text"))
        assertFalse(result.has("next_offset"))
        file.writeText("a".repeat(5999) + "😀" + "后续")
        val first = tools.execute("read_text", JSONObject().put("path", "large.txt"))
        assertEquals("😀", first.getString("text").takeLast(2))
        assertEquals("后续", tools.execute("read_text", JSONObject().put("path", "large.txt")
            .put("offset", first.getInt("next_offset"))).getString("text"))
    }

    @Test fun documentReferencesSurviveCodecAndSessionRoundTripWithoutInlineContent() {
        val digest = "ab".repeat(32)
        val file = TextAttachment("local", "完整讲义.pdf", "", digest, document = digest, pageCount = 120)
        assertEquals(listOf(file), AttachmentCodec.decode(AttachmentCodec.encode(listOf(file))))
        val task = SessionTask(title = "讲解第100页", attachments = listOf(file))
        val session = TaskSession(title = "文档", goal = "", tasks = listOf(task), mode = ConversationMode.CHAT)
        assertEquals(file, SessionCodec.decode(SessionCodec.encode(session)).tasks.single().attachments.single())
        assertTrue(runCatching { file.copy(text = "其他正文") }.isFailure)
        assertTrue(runCatching { file.copy(sha256 = "cd".repeat(32)) }.isFailure)
    }

    @Test fun automaticFileDecisionAcceptsAnArchiveEntryAndImagesUseTheirSourcePage() {
        val decision = DecisionParser.parse("""{"decision":"file","file_tool":"read_file","path":"phone/primary/a.zip","entry":"notes/a.pdf","offset":8}""") as AgentDecision.FileRead
        assertEquals("notes/a.pdf", decision.entry)
        assertEquals(8, decision.offset)
        val result = JSONObject().put("path", "phone/primary/a.pdf").put("page_images", JSONArray()
            .put(JSONObject().put("page", 9).put("digest", "ab".repeat(32))))
        val content = JSONArray()
        FileVisuals.append(content, result) { "data:image/jpeg;base64,TEST" }
        assertTrue(content.getJSONObject(0).getString("text").contains("第 9 页"))
        assertEquals("data:image/jpeg;base64,TEST", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }
}

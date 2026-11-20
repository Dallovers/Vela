package com.dallovers.phoneagent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhoneWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun nestedEditPreservesBomAndRefusesToReplaceChangedOrExistingFile() {
        val root = temp.newFolder()
        val dir = File(root, "notes").apply { mkdir() }
        val note = File(dir, "笔记.md").apply { writeText("\uFEFF旧稿") }
        val files = WorkspaceFiles(root, "Documents")
        assertTrue(files.entries("").single().directory)
        assertEquals(note.name, files.entries("notes").single().name)
        files.save("notes/笔记.md", "新稿", expected = "旧稿")
        assertEquals("\uFEFF新稿", note.readText())
        assertTrue(runCatching { files.save("notes/笔记.md", "覆盖", expected = "旧稿") }.isFailure)
        assertTrue(runCatching { files.save("notes/笔记.md", "覆盖", create = true) }.isFailure)
        assertEquals("\uFEFF新稿", note.readText())
    }

    @Test fun checkedNamesProtectionAndRevocationApplyToAllReads() {
        val root = temp.newFolder()
        File(root, "Android/data").mkdirs()
        File(root, "Android/obb").mkdirs()
        File(root, "Android/media").mkdirs()
        var granted = true
        val files = WorkspaceFiles(root, "") { check(granted) }
        assertEquals(listOf("media"), files.entries("Android").map { it.name })
        for (path in listOf("Android/data", "Android/Obb", "../outside", "/sdcard", "a//b", "a\\..\\b"))
            assertTrue(path, runCatching { files.entries(path) }.isFailure)
        granted = false
        assertTrue(runCatching { files.entries("") }.isFailure)
        assertTrue(runCatching { files.save("note.txt", "", create = true) }.isFailure)
        assertFalse(File(root, "note.txt").exists())
    }

    @Test fun toolsAreScopedToSelectedSubfolderAndPaginateWithoutExposingSiblings() {
        val root = temp.newFolder()
        File(root, "ignored.txt").writeText("outside")
        val dir = File(root, "demo").apply { mkdir() }
        repeat(41) { File(dir, "note-$it").writeText("a".repeat(6500)) }
        val tools = WorkspaceFileTools(WorkspaceFiles(root, ""), "demo")
        fun args(path: String, offset: Int = 0) = JSONObject().put("path", path).put("offset", offset)
        val first = tools.execute("list_directory", args(""))
        assertEquals(41, first.getInt("total"))
        val second = tools.execute("list_directory", args("", first.getInt("next_offset")))
        assertEquals(1, second.getJSONArray("entries").length())
        assertEquals(500, tools.execute("read_text", args("note-1", 6000)).getString("text").length)
        assertTrue(runCatching { tools.execute("read_text", args("../ignored.txt")) }.isFailure)
        assertTrue(runCatching { tools.execute("read_text", args("ignored.txt")) }.isFailure)
    }

    @Test fun fileConversationPersistsScopeAndReturnsToolResultsToTheModel() = runBlocking {
        val root = temp.newFolder()
        val dir = File(root, "demo").apply { mkdir() }
        File(dir, "a.txt").writeText("专用于测试")
        val session = TaskSession(title = "测试", goal = "", tasks = emptyList(), mode = ConversationMode.CHAT, fileChatDirectory = "demo")
        val persisted = SessionCodec.encode(session)
        assertEquals("demo", SessionCodec.decode(persisted).fileChatDirectory)
        persisted.remove("fileChatDirectory")
        assertNull(SessionCodec.decode(persisted).fileChatDirectory)
        val messages = ChatProtocol.messages(session, SessionTask(title = "读一下 a.txt"))
        var round = 0
        val result = answerWithFiles(messages, WorkspaceFileTools(WorkspaceFiles(root, ""), "demo")) { history, _ ->
            if (round++ == 0) {
                assertFalse(history.toString().contains("专用于测试"))
                val call = JSONObject().put("id", "tool1").put("type", "function").put("function", JSONObject()
                    .put("name", "read_text").put("arguments", JSONObject().put("path", "a.txt").toString()))
                JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
                    .put("role", "assistant").put("tool_calls", JSONArray().put(call)))
            } else {
                val tool = history.getJSONObject(history.length() - 1)
                assertEquals("tool1", tool.getString("tool_call_id"))
                assertEquals("专用于测试", JSONObject(tool.getString("content")).getString("text"))
                JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", "已读取 a.txt"))
            }
        }
        assertEquals("已读取 a.txt", result)
    }
}

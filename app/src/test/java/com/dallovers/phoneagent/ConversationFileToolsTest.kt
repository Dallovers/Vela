package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConversationFileToolsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun ordinaryConversationReadsSharedFilesWithoutAnActiveFolderChat() {
        val workspace = temp.newFolder("workspace")
        val phone = temp.newFolder("phone")
        File(phone, "Download").mkdir()
        File(phone, "Download/demo.txt").writeText("synthetic-file-036")
        val files = ConversationFileTools({ WorkspaceFileTools(WorkspaceFiles(workspace, ""), "") },
            mapOf("primary" to { WorkspaceFileTools(WorkspaceFiles(phone, ""), "") }))
        val root = files.execute("list_directory", JSONObject().put("path", "")).getJSONArray("entries")
        assertEquals(listOf("workspace", "phone"), List(root.length()) { root.getJSONObject(it).getString("name") })
        val result = files.execute("read_text", JSONObject().put("path", "phone/primary/Download/demo.txt"))
        assertEquals("synthetic-file-036", result.getString("text"))
        assertEquals("phone/primary/Download/demo.txt", result.getString("path"))
        assertTrue(workspace.listFiles()!!.isEmpty())
    }

    @Test fun sharedReadsRecheckPermissionAndCannotEscapeIntoProtectedOrUnlistedLocations() {
        val root = temp.newFolder()
        File(root, "Android/data").mkdirs()
        var granted = true
        val files = ConversationFileTools({ error("unused workspace") }, mapOf("primary" to {
            check(granted)
            WorkspaceFileTools(WorkspaceFiles(root, ""), "")
        }))
        for (path in listOf("phone/primary/../outside", "phone/primary/Android/data", "phone/other", "/data/data")) {
            assertTrue(path, runCatching { files.execute("list_directory", JSONObject().put("path", path)) }.isFailure)
        }
        granted = false
        assertTrue(runCatching { files.execute("list_directory", JSONObject().put("path", "phone/primary")) }.isFailure)
    }

    @Test fun phoneToolTransportSupportsDirectReadDecisionAndRejectsWritesOrMixedGestures() {
        val arguments = """{"decision":"file","file_tool":"read_text","path":"phone/primary/Download/demo.txt","offset":0,"reason":"读取指定文件"}"""
        val message = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("id", "read-1")
            .put("type", "function").put("function", JSONObject().put("name", "phone_action").put("arguments", arguments))))
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", message)))
        val decision = DecisionParser.parse(ModelReply.parse(body.toString(), true).content) as AgentDecision.FileRead
        assertEquals("read_text", decision.tool)
        assertEquals("phone/primary/Download/demo.txt", decision.path)
        for (invalid in listOf(arguments.replace("read_text", "delete_file"), arguments.replace("\"offset\":0", "\"offset\":-1"),
            arguments.replace("\"offset\":0", "\"offset\":0.5"), arguments.replace("\"decision\":\"file\"", "\"decision\":\"file\",\"action\":\"tap\""))) {
            assertTrue(runCatching { DecisionParser.parse(invalid) }.isFailure)
        }
        val history = ToolConversation()
        history.received(message)
        history.outcome("""{"text":"synthetic-file-036"}""")
        assertEquals("read-1", history.messages().last().getString("tool_call_id"))
        assertEquals("synthetic-file-036", JSONObject(history.messages().last().getString("content")).getString("text"))
    }
}

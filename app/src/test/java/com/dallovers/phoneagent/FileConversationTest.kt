package com.dallovers.phoneagent

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileConversationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val other = "fb6436cf-309f-43c9-846d-63c0a2ad25a1"
    private fun attachment(text: String = "内容") = TextAttachment.snapshot("local", "笔记.txt", text)

    @Test fun legacyWorkspaceStaysAtOriginalDirectoryAndNewWorkspaceIsSeparate() {
        val base = temporary.newFolder()
        assertEquals(java.io.File(base, "workspace").canonicalFile, WorkspacePaths.root(base, "local"))
        assertNotEquals(WorkspacePaths.root(base, "local"), WorkspacePaths.root(base, other))
        listOf("../private", "../../", "/tmp", "a/b").forEach { assertTrue(runCatching { WorkspacePaths.root(base, it) }.isFailure) }
    }
    @Test fun queuedPayloadDoesNotReadAChangedSourceFile() {
        val source = temporary.newFile("source.txt").apply { writeText("Original030") }
        val file = attachment(source.readText())
        val session = TaskSession(title = "测试", goal = "", tasks = emptyList(), mode = ConversationMode.CHAT).queueMessage("读文件", listOf(file))
        source.writeText("ChangedLater")
        val request = ChatProtocol.messages(session, session.tasks.single()).toString()
        assertTrue(request.contains("Original030"))
        assertFalse(request.contains("ChangedLater"))
    }
    @Test fun attachmentRoundTripAndHashDetectTampering() {
        val files = listOf(attachment())
        assertEquals(files, AttachmentCodec.decode(AttachmentCodec.encode(files)))
        val broken = AttachmentCodec.encode(files).apply { getJSONObject(0).put("text", "偷偷修改") }
        assertTrue(runCatching { AttachmentCodec.decode(broken) }.isFailure)
    }
    @Test fun attachmentBudgetUsesUtf8BytesAndChecksWholeBatch() {
        assertTrue(runCatching { attachment("你".repeat(22000)) }.isFailure)
        val a = attachment("a".repeat(40000))
        val b = TextAttachment.snapshot("local", "b.txt", "b".repeat(40000))
        assertTrue(runCatching { TextAttachment.validateBatch(listOf(a, b), "local") }.isFailure)
    }
    @Test fun crossWorkspaceAndDuplicateFileSelectionsAreRejected() {
        assertTrue(runCatching { TextAttachment.validateBatch(listOf(attachment()), other) }.isFailure)
        assertTrue(runCatching { TextAttachment.validateBatch(listOf(attachment(), attachment()), "local") }.isFailure)
    }
    @Test fun currentRequestDoesNotIncludeFutureQueuedFile() {
        val current = SessionTask(id = "current", title = "现在", attachments = listOf(attachment("CURRENT")))
        val future = SessionTask(id = "future", title = "以后", attachments = listOf(attachment("FUTURE_SECRET")))
        val session = TaskSession(title = "测试", goal = "", mode = ConversationMode.CHAT, tasks = listOf(current, future),
            messages = listOf(ConversationMessage(role = "user", text = current.title, taskId = current.id), ConversationMessage(role = "user", text = future.title, taskId = future.id)))
        val body = ChatProtocol.messages(session, current).toString()
        assertTrue(body.contains("CURRENT")); assertFalse(body.contains("FUTURE_SECRET"))
    }
    @Test fun sessionMigrationAndRetryKeepWorkspaceAndFileSnapshot() {
        val file = TextAttachment.snapshot(other, "a.txt", "Original")
        val task = SessionTask(id = "task", title = "问题", status = TaskStatus.COMPLETED, result = "答", attachments = listOf(file))
        val session = TaskSession(title = "测试", goal = "问题", mode = ConversationMode.CHAT, workspaceId = other, tasks = listOf(task),
            messages = listOf(ConversationMessage(id = "u", role = "user", text = "问题", taskId = task.id), ConversationMessage(id = "a", role = "assistant", text = "答", taskId = task.id)))
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        val retry = session.chatBranch("a", true)
        assertEquals(other, retry.workspaceId); assertEquals(listOf(file), retry.tasks.single().attachments)
        val legacy = SessionCodec.encode(session.copy(workspaceId = "local", tasks = listOf(task.copy(attachments = emptyList())))).put("schema", 4).apply { remove("workspaceId"); remove("savedFiles") }
        assertEquals("local", SessionCodec.decode(legacy).workspaceId)
    }
    @Test fun emptyFileConversationHasNoSyntheticEmptyUserMessage() {
        assertTrue(TaskSession(title = "文件", goal = "", tasks = emptyList(), mode = ConversationMode.CHAT).conversationMessages().isEmpty())
    }
    @Test fun codeFencesAreOfferedAsDataAndLongFencesPreserveInnerTicks() {
        val parts = ReplyFiles.parts("说明\n````txt\n```literal\n````\n```python\nprint(1)\n```")
        assertEquals(3, parts.size)
        assertEquals("```literal\n", parts[1].text)
        assertEquals("py", parts[2].extension)
        assertEquals("print(1)\n", parts[2].text)
        assertEquals(1, ReplyFiles.parts("```python\nnot closed").size)
    }
}

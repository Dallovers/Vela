package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class ChatBranchTest {
    private val first = SessionTask(id = "first", title = "第一问", status = TaskStatus.COMPLETED, result = "第一答")
    private val second = SessionTask(id = "second", title = "未来的要求", status = TaskStatus.COMPLETED, result = "未来答")
    private val session = TaskSession(title = "测试", goal = "原目标", tasks = listOf(first, second),
        mode = ConversationMode.CHAT, summary = "包含未来信息的摘要", folder = "学习", pinned = true, archived = true,
        messages = listOf(
            ConversationMessage(id = "u1", role = "user", text = first.title, taskId = first.id),
            ConversationMessage(id = "a1", role = "assistant", text = first.result, taskId = first.id),
            ConversationMessage(id = "u2", role = "user", text = second.title, taskId = second.id),
            ConversationMessage(id = "a2", role = "assistant", text = second.result, taskId = second.id)))

    @Test fun branchIsIndependentAndDropsFutureMemoryAndPendingActions() {
        val branch = session.chatBranch("a1")
        assertNotEquals(session.id, branch.id)
        assertEquals(listOf("u1", "a1"), branch.messages.map { it.id })
        assertEquals(listOf(first), branch.tasks)
        assertEquals("", branch.summary)
        assertEquals("学习", branch.folder)
        assertFalse(branch.archived)
        assertFalse(branch.pinned)
        assertEquals(SessionStatus.COMPLETED, branch.status)
        assertEquals(4, session.messages.size)
    }

    @Test fun retryKeepsEarlierPairsButRemovesSelectedAnswerAndFuture() {
        val retry = session.chatBranch("a2", retry = true)
        assertEquals(listOf("u1", "a1", "u2"), retry.messages.map { it.id })
        assertEquals(first, retry.tasks[0])
        assertEquals(TaskStatus.TODO, retry.tasks[1].status)
        assertEquals("", retry.tasks[1].result)
        val request = ChatProtocol.messages(retry, retry.tasks.last()).toString()
        assertTrue(request.contains("第一答"))
        assertFalse(request.contains("未来答"))
        assertFalse(request.contains("包含未来信息的摘要"))
    }

    @Test(expected = IllegalArgumentException::class) fun phoneOperationsCannotBeRetriedAsChat() {
        session.copy(mode = ConversationMode.PHONE).chatBranch("a1", retry = true)
    }

    @Test(expected = IllegalArgumentException::class) fun staleMessageDoesNotCreateEmptyBranch() {
        session.chatBranch("missing")
    }

    @Test fun modeChangesNeverResumePendingActions() {
        assertTrue(session.copy(status = SessionStatus.COMPLETED).canChangeMode())
        assertFalse(session.copy(status = SessionStatus.RUNNING).canChangeMode())
        assertFalse(session.copy(tasks = listOf(first.copy(status = TaskStatus.PAUSED))).canChangeMode())
    }

    @Test fun organizationRoundTripsAndLegacyDefaultsStayEmpty() {
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        val old = SessionCodec.encode(session).put("schema", 3).apply { remove("folder"); remove("pinned") }
        val decoded = SessionCodec.decode(old)
        assertEquals("", decoded.folder)
        assertFalse(decoded.pinned)
        assertEquals(ConversationMode.CHAT, decoded.mode)
        assertTrue(decoded.archived)
    }
}

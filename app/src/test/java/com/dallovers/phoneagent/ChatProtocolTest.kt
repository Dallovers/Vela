package com.dallovers.phoneagent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    @Test fun legacySessionsRemainPhoneSessions() {
        val session = TaskSession(title = "旧任务", goal = "目标", tasks = emptyList())
        val old = SessionCodec.encode(session).put("schema", 2).apply { remove("mode"); remove("archived") }
        assertEquals(ConversationMode.PHONE, SessionCodec.decode(old).mode)
        assertFalse(SessionCodec.decode(old).archived)
        val chat = session.copy(mode = ConversationMode.CHAT, archived = true)
        assertEquals(chat, SessionCodec.decode(SessionCodec.encode(chat)))
    }

    @Test fun contextOmitsFutureAndInterruptedRequestsAndHasNoTools() {
        val done = SessionTask(id = "done", title = "旧问", status = TaskStatus.COMPLETED)
        val current = SessionTask(id = "current", title = "新要求")
        val future = SessionTask(id = "future", title = "之后再问")
        val session = TaskSession(title = "聊天", goal = "旧目标", mode = ConversationMode.CHAT,
            tasks = listOf(done, current, future), messages = listOf(
                ConversationMessage(role = "user", text = "旧问", taskId = done.id),
                ConversationMessage(role = "assistant", text = "旧答", taskId = done.id),
                ConversationMessage(role = "user", text = "被取消", taskId = "cancelled"),
                ConversationMessage(role = "user", text = future.title, taskId = future.id)))
        val messages = ChatProtocol.messages(session, current)
        assertEquals(4, messages.length())
        assertEquals("新要求", messages.getJSONObject(3).getString("content"))
        assertFalse(messages.toString().contains("之后再问"))
        assertFalse(messages.toString().contains("被取消"))
    }

    @Test fun streamOnlyDisplaysContentAndAcceptsUsageTail() {
        val stream = ChatStream()
        assertFalse(stream.accept("""{"choices":[{"delta":{"reasoning_content":"private","content":null}}]}"""))
        assertTrue(stream.accept("""{"choices":[{"delta":{"content":"你好"}}]}"""))
        stream.accept("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        stream.accept("""{"choices":[],"usage":{"total_tokens":42}}""")
        assertEquals("你好", stream.completedText())
    }

    @Test(expected = IllegalArgumentException::class) fun truncatedStreamIsNotSuccess() {
        val stream = ChatStream()
        stream.accept("""{"choices":[{"delta":{"content":"一半"},"finish_reason":"length"}]}""")
        stream.completedText()
    }

    @Test(expected = IllegalArgumentException::class) fun disconnectedStreamIsNotSuccess() {
        val stream = ChatStream()
        stream.accept("""{"choices":[{"delta":{"content":"一半"}}]}""")
        stream.completedText()
    }

    @Test fun exportIncludesOnlyVisibleMessages() {
        val session = TaskSession(title = "测试", goal = "目标", summary = "隐藏摘要", tasks = emptyList(),
            messages = listOf(ConversationMessage(role = "user", text = "你好"), ConversationMessage(role = "assistant", text = "你好呀")))
        assertTrue(session.exportMarkdown().contains("你好呀"))
        assertFalse(session.exportMarkdown().contains("隐藏摘要"))
    }
}

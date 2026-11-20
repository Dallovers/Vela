package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExecutionContextTest {
    @Test fun oldGoalDoesNotRemainTheActiveRequestAndRepliesStayWithTheirTask() {
        val old = SessionTask(id = "old", title = "不要发送", status = TaskStatus.COMPLETED)
        val current = SessionTask(id = "new", title = "现在发送测试消息")
        val session = TaskSession(title = "test", goal = "不要发送", summary = "旧信息", tasks = listOf(old, current), messages = listOf(
            ConversationMessage(role = "user", text = "不发送", taskId = old.id),
            ConversationMessage(role = "user", text = "确认发给测试联系人", taskId = current.id)))
        val context = JSONObject(session.executionContext(current))
        assertEquals(current.title, context.getString("current_request"))
        assertEquals(listOf("确认发给测试联系人"), context.getJSONArray("current_user_replies").let { (0 until it.length()).map(it::getString) })
        assertEquals("不要发送", context.getJSONObject("background_only").getString("initial_goal"))
    }

    @Test fun steeringRetiresOldTaskAheadOfQueueAndKeepsCompletedWork() {
        val done = SessionTask(title = "已完成", status = TaskStatus.COMPLETED)
        val active = SessionTask(title = "旧要求", status = TaskStatus.RUNNING)
        val queued = SessionTask(title = "下一件事")
        val revised = TaskSession(title = "test", goal = "旧要求", tasks = listOf(done, active, queued), status = SessionStatus.RUNNING)
            .replaceCurrentRequest("新的要求")
        assertEquals(done, revised.tasks[0])
        assertEquals(TaskStatus.SKIPPED, revised.tasks[1].status)
        assertEquals("新的要求", revised.tasks[2].title)
        assertEquals(queued, revised.tasks[3])
        assertEquals(SessionStatus.READY, revised.status)
        assertEquals(revised.tasks[2].id, revised.messages.last().taskId)
    }

    private fun toolMessage(id: String) = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
        .put("reasoning_content", "opaque provider reasoning $id")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "phone_action").put("arguments", "{}"))))

    @Test fun reasoningAndToolIdsRoundTripWithActualOutcomeAndResetBetweenTasks() {
        val history = ToolConversation()
        history.received(toolMessage("call1"))
        history.outcome("未执行，旧屏幕")
        history.received(toolMessage("call2"))
        history.outcome("已提交，结果待核对")
        val messages = history.messages()
        assertEquals(4, messages.size)
        assertEquals("opaque provider reasoning call1", messages[0].getString("reasoning_content"))
        assertEquals("call1", messages[1].getString("tool_call_id"))
        assertEquals("未执行，旧屏幕", messages[1].getString("content"))
        assertEquals("call2", messages[3].getString("tool_call_id"))
        history.reset()
        assertTrue(history.messages().isEmpty())
    }

    @Test fun checkpointNeverKeepsPartialToolExchange() {
        val history = ToolConversation()
        repeat(24) { history.received(toolMessage("call$it")); history.outcome("done") }
        assertTrue(history.messages().isEmpty())
        history.received(toolMessage("fresh")); history.outcome("new")
        assertEquals(2, history.messages().size)
    }

    @Test fun contentDecisionAlsoRetainsProviderReasoningForNextToolRound() {
        val history = ToolConversation()
        history.received(JSONObject().put("role", "assistant").put("content", "{\"decision\":\"act\"}").put("reasoning_content", "opaque"))
        history.outcome("没有工具调用编号")
        assertEquals("opaque", history.messages().single().getString("reasoning_content"))
    }

    @Test fun thinkingMustNotForceToolChoiceAndNonOfficialGetsNoDeepSeekFields() {
        val thinking = ModelProtocol.options(JSONObject(), official = true, thinking = true, actionTool = JSONObject())
        assertEquals("auto", thinking.getString("tool_choice"))
        assertEquals("enabled", thinking.getJSONObject("thinking").getString("type"))
        assertEquals("high", thinking.getString("reasoning_effort"))
        val disabled = ModelProtocol.options(JSONObject(), official = true, thinking = false, actionTool = JSONObject())
        assertEquals("phone_action", disabled.getJSONObject("tool_choice").getJSONObject("function").getString("name"))
        assertFalse(disabled.has("reasoning_effort"))
        val compatible = ModelProtocol.options(JSONObject(), official = false, thinking = true, actionTool = null)
        assertFalse(compatible.has("thinking")); assertFalse(compatible.has("reasoning_effort")); assertFalse(compatible.has("tools"))
    }
}

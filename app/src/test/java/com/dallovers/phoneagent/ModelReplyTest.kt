package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelReplyTest {
    private fun tool(name: String = "phone_action", arguments: Any = """{"action":"home"}""") =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments))

    private fun reply(message: JSONObject, finish: String = "tool_calls") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", message).put("finish_reason", finish))).toString()

    @Test
    fun readsOnlyStructuredToolArgumentsEvenWithExtraAssistantContent() {
        val payload = reply(JSONObject().put("content", "执行动作").put("tool_calls", JSONArray().put(tool())))
        val result = ModelReply.parse(payload, true)
        assertEquals("tool_call", result.transport)
        assertEquals("home", AgentAction.parse(result.content).kind)
    }

    @Test
    fun rejectsMultipleCallsWithoutChoosingFirst() {
        rejected("multiple_tools", reply(JSONObject().put("tool_calls", JSONArray().put(tool()).put(tool()))))
    }

    @Test
    fun rejectsUnknownToolAndNonStringArguments() {
        rejected("unknown_tool", reply(JSONObject().put("tool_calls", JSONArray().put(tool(name = "other")))))
        rejected("invalid_arguments", reply(JSONObject().put("tool_calls", JSONArray().put(tool(arguments = JSONObject())))))
    }

    @Test
    fun rejectsPlainContentWhenFunctionWasRequired() {
        rejected("missing_tool_call", reply(JSONObject().put("content", """{"action":"home"}"""), "stop"))
    }

    @Test
    fun rejectsTruncationAndIncompleteEnvelopes() {
        rejected("truncated_reply", reply(JSONObject().put("tool_calls", JSONArray().put(tool())), "length"))
        rejected("invalid_envelope", "{}")
    }

    @Test
    fun malformedToolArgumentsAreRejectedByActionValidation() {
        val parsed = ModelReply.parse(reply(JSONObject().put("tool_calls", JSONArray().put(tool(arguments = "{\"action\":")))), true)
        try {
            AgentAction.parse(parsed.content)
            fail("Truncated action must not execute")
        } catch (error: ModelActionRejectedException) {
            assertEquals("invalid_json", error.code)
        }
    }

    @Test
    fun retainsPlainContentSupportForOtherProviders() {
        val parsed = ModelReply.parse(reply(JSONObject().put("content", """{"action":"back"}"""), "stop"), false)
        assertEquals("content", parsed.transport)
        assertEquals("back", AgentAction.parse(parsed.content).kind)
    }

    @Test
    fun parsesActionQuestionAndCompletionAsDifferentDecisions() {
        val action = DecisionParser.parse("""{"decision":"act","action":{"action":"back","reason":"返回"}}""")
        assertTrue(action is AgentDecision.Act)
        assertEquals("back", (action as AgentDecision.Act).action.kind)

        val question = DecisionParser.parse("""{"decision":"ask_user","prompt":"选择哪个页面","reason":"有多个候选目标","options":["设置","练习"]}""")
        assertTrue(question is AgentDecision.AskUser)
        assertEquals(listOf("设置", "练习"), (question as AgentDecision.AskUser).options)

        val complete = DecisionParser.parse("""{"decision":"complete","result":"已核对输入框内容"}""")
        assertEquals("已核对输入框内容", (complete as AgentDecision.Complete).result)
    }

    @Test
    fun rejectsQuestionWithoutReasonAndUnknownDecision() {
        rejectedDecision("invalid_question", """{"decision":"ask_user","prompt":"请选择"}""")
        rejectedDecision("unknown_decision", """{"decision":"execute_all","reason":"no"}""")
        rejectedDecision("mixed_decision", """{"decision":"ask_user","prompt":"选择","reason":"歧义","action":"tap"}""")
        rejectedDecision("invalid_decision", """{"decision":"complete","result":"完成"} {"action":"home"}""")
        rejectedDecision("missing_result", """{"decision":"complete"}""")
    }

    private fun rejectedDecision(code: String, body: String) {
        try { DecisionParser.parse(body); fail("Invalid decision must be rejected") }
        catch (error: ModelActionRejectedException) { assertEquals(code, error.code) }
    }

    private fun rejected(code: String, payload: String) {
        try {
            ModelReply.parse(payload, true)
            fail("Invalid reply must not execute")
        } catch (error: ModelActionRejectedException) {
            assertEquals(code, error.code)
        }
    }
}

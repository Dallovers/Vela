package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ModelReply(val content: String, val finish: String, val transport: String) {
    companion object {
        fun parse(payload: String, expectTool: Boolean): ModelReply = try {
            val choice = JSONObject(payload).getJSONArray("choices").getJSONObject(0)
            val finish = choice.optString("finish_reason").takeIf {
                it in setOf("stop", "length", "content_filter", "tool_calls")
            } ?: "other"
            if (finish == "length") throw ModelActionRejectedException("truncated_reply", "模型回复被截断，已拒绝执行")
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                if (calls.length() != 1) throw ModelActionRejectedException("multiple_tools", "模型返回了多个操作工具，本次均未执行")
                val function = calls.getJSONObject(0).getJSONObject("function")
                if (function.getString("name") != "phone_action") {
                    throw ModelActionRejectedException("unknown_tool", "模型返回了未声明的操作工具")
                }
                val arguments = function.opt("arguments") as? String
                    ?: throw ModelActionRejectedException("invalid_arguments", "模型工具参数不是 JSON 字符串")
                ModelReply(arguments, finish, "tool_call")
            } else {
                if (expectTool) throw ModelActionRejectedException("missing_tool_call", "模型未返回指定的操作工具，本次未执行")
                val content = when (val value = message.opt("content")) {
                    is String -> value
                    is JSONArray -> (0 until value.length()).joinToString("") { value.getJSONObject(it).optString("text") }
                    else -> ""
                }
                ModelReply(content, finish, "content")
            }
        } catch (_: JSONException) {
            throw ModelActionRejectedException("invalid_envelope", "模型接口响应的 choices/message 或工具结构不完整")
        }
    }
}

sealed interface AgentDecision {
    data class Act(val action: AgentAction) : AgentDecision
    data class FileRead(val tool: String, val path: String, val offset: Int, val entry: String = "") : AgentDecision
    data class AskUser(val prompt: String, val reason: String, val options: List<String>) : AgentDecision
    data class Complete(val result: String) : AgentDecision
}

object DecisionParser {
    fun parse(content: String): AgentDecision {
        var body = content.trim().removePrefix("\uFEFF").trim()
        Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)
            .matchEntire(body)?.let { body = it.groupValues[1].trim() }
        val json = try {
            val reader = org.json.JSONTokener(body)
            val value = reader.nextValue()
            if (value !is JSONObject || reader.nextClean() != '\u0000') {
                throw ModelActionRejectedException("invalid_decision", "决策必须是唯一完整的 JSON 对象")
            }
            value
        } catch (_: JSONException) {
            throw ModelActionRejectedException("invalid_json", "模型决策 JSON 格式有误")
        }
        val decision = json.optString("decision", "act")
        if (decision != "act" && json.has("action")) throw ModelActionRejectedException("mixed_decision", "询问或完成决策不能同时包含动作")
        return when (decision) {
            "act" -> AgentDecision.Act(AgentAction.parse(json.optJSONObject("action")?.toString() ?: content))
            "file" -> {
                val tool = json.optString("file_tool")
                val path = json.opt("path") as? String
                val offset = json.opt("offset") ?: 0
                if (tool !in setOf("list_directory", "read_text", "read_file") || path == null || path.length > 4096 ||
                    offset !is Number || offset.toDouble() !in 0.0..Int.MAX_VALUE.toDouble() || offset.toDouble() != offset.toInt().toDouble())
                    throw ModelActionRejectedException("invalid_file_request", "文件请求需要有效的工具、路径及非负整数 offset")
                AgentDecision.FileRead(tool, path, offset.toInt(), json.optString("entry").also { if (it.length > 4096) throw ModelActionRejectedException("invalid_entry", "压缩条目名过长") })
            }
            "ask_user" -> {
                val prompt = json.optString("prompt").trim()
                val reason = json.optString("reason").trim()
                if (prompt.isBlank() || reason.isBlank()) throw ModelActionRejectedException("invalid_question", "模型询问缺少问题或原因")
                val options = json.optJSONArray("options")?.let { rows ->
                    (0 until minOf(rows.length(), 5)).map { rows.optString(it).trim() }.filter(String::isNotBlank)
                }.orEmpty()
                AgentDecision.AskUser(prompt.take(500), reason.take(500), options)
            }
            "complete" -> {
                val result = (json.opt("result") as? String)?.trim().orEmpty()
                if (result.isBlank()) throw ModelActionRejectedException("missing_result", "完成决策必须包含观察结果")
                if (ClarificationGuard.needsReply(result)) AgentDecision.AskUser(result.take(500), "助手需要你的补充，操作已暂停。", emptyList())
                else {
                    if (result.length > 100000) throw ModelActionRejectedException("result_too_long", "讲解内容过长，请分段回答")
                    AgentDecision.Complete(result)
                }
            }
            else -> throw ModelActionRejectedException("unknown_decision", "模型返回了不支持的决策类型")
        }
    }
}

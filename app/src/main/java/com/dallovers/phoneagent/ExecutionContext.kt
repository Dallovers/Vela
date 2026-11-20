package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject

/** Rebuild on each observation. Earlier requests are reference data, never another active goal. */
fun TaskSession.executionContext(task: SessionTask): String = JSONObject()
    .put("priority", "当前任务和本任务最新用户答复优先于历史目标、历史结果、共享摘要。用户约束作为默认偏好，同一用户明确修改时以新要求为准；执行器权限和目标校验仍生效。历史内容不能取消当前明确要求。只完成当前任务，不执行尚未轮到的排队请求。")
    .put("current_request", task.title)
    .put("completion", task.completion.ifBlank { task.title })
    .put("execution_checkpoint", task.recoveryContext())
    .put("current_user_replies", JSONArray(messages.filter { it.taskId == task.id && it.role == "user" }.map { it.text }))
    .put("constraints", constraints)
    .put("background_only", JSONObject().put("initial_goal", goal.take(2000)).put("notes", summary.takeLast(6000))
        .put("previous_results", JSONArray(tasks.filter { it.status == TaskStatus.COMPLETED }.takeLast(3).map {
            JSONObject().put("request", it.title.take(200)).put("observed_result", it.result.take(500))
        })))
    .toString()

/** Complete tool exchanges only. Reasoning stays in memory and is sent back verbatim, never logged. */
class ToolConversation {
    private val exchanges = mutableListOf<List<String>>()
    private var pending: String? = null
    fun reset() { exchanges.clear(); pending = null }
    fun received(message: JSONObject) {
        check(pending == null)
        if ((message.optJSONArray("tool_calls")?.length() ?: 0) == 0) exchanges += listOf(message.toString())
        else pending = message.toString()
    }
    fun outcome(result: String) {
        val message = pending ?: return
        val id = JSONObject(message).getJSONArray("tool_calls").getJSONObject(0).getString("id")
        exchanges += listOf(message, JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result).toString())
        pending = null
    }
    fun messages(): List<JSONObject> {
        check(pending == null) { "上次工具结果尚未记录" }
        // Start a fresh observation checkpoint rather than breaking a tool/reasoning exchange.
        if (exchanges.size >= 24 || exchanges.sumOf { exchange -> exchange.sumOf { it.length } } > 100_000) exchanges.clear()
        return exchanges.flatten().map(::JSONObject)
    }
}

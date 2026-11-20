package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject

/** Provider-specific fields must not leak into otherwise OpenAI-compatible endpoints. */
object ModelProtocol {
    fun options(body: JSONObject, official: Boolean, thinking: Boolean, actionTool: JSONObject?): JSONObject = body.apply {
        if (actionTool != null) {
            put("tools", JSONArray().put(actionTool))
            put("tool_choice", if (thinking) "auto" else JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", "phone_action")))
        }
        if (official) {
            put("thinking", JSONObject().put("type", if (thinking) "enabled" else "disabled"))
            if (thinking) put("reasoning_effort", "high")
        }
    }
}

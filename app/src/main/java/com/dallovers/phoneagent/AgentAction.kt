package com.dallovers.phoneagent

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class ModelActionRejectedException(val code: String, message: String) : IllegalArgumentException(message)

data class AgentAction(
    val kind: String,
    val reason: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val endX: Double = 0.0,
    val endY: Double = 0.0,
    val duration: Long = 500,
    val text: String = "",
    val direction: String = "",
    val targetId: String = "",
    val screenId: String = "",
) {
    companion object {
        fun parse(content: String): AgentAction {
            var body = content.trim().removePrefix("\uFEFF").trim()
            Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$", RegexOption.IGNORE_CASE)
                .matchEntire(body)?.let { body = it.groupValues[1].trim() }
            if (body.isEmpty()) reject("empty_reply", "模型返回空动作，已请求重新生成")
            val json = try {
                val reader = JSONTokener(body)
                val value = reader.nextValue()
                if (value !is JSONObject) reject("non_object_reply", "模型回复的根类型为 ${value?.javaClass?.simpleName ?: "null"}，需返回一个动作对象")
                if (reader.nextClean() != '\u0000') reject("trailing_content", "模型动作对象后还有额外内容，已拒绝执行")
                value
            } catch (_: JSONException) {
                reject("invalid_json", "模型返回的 JSON 不完整或语法有误")
            }
            fun requiredString(name: String): String = (json.opt(name) as? String)
                ?: reject("missing_$name", "模型动作缺少有效的 $name 字段")
            val kind = requiredString("action")
            if (kind !in setOf("tap", "tap_element", "tap_key", "long_press", "swipe", "scroll", "type_text", "back", "home", "open_settings", "open_app", "read_copied_link", "wait", "finish")) {
                reject("unknown_action", "模型返回了不支持的动作")
            }
            fun coordinate(name: String): Double {
                val value = when (val raw = json.opt(name)) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                } ?: reject("missing_$name", "模型动作缺少有效的 $name 坐标")
                if (!value.isFinite() || value !in 0.0..1000.0) {
                    reject("invalid_coordinates", "模型坐标超出范围，需按整张截图归一化到 0..1000，不能使用像素坐标")
                }
                return value / 1000.0
            }
            val hasPoint = kind in setOf("tap", "long_press", "swipe")
            val x = if (hasPoint) coordinate("x") else 0.0
            val y = if (hasPoint) coordinate("y") else 0.0
            val endX = if (kind == "swipe") coordinate("end_x") else 0.0
            val endY = if (kind == "swipe") coordinate("end_y") else 0.0
            val text = when (kind) {
                "open_app" -> requiredString("package_name").also {
                    if (!Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(it)) reject("invalid_package", "应用包名格式无效")
                }
                "type_text" -> requiredString("text")
                "tap_key" -> requiredString("key").also {
                    if (it.length != 1 || it[0] !in "0123456789*#") reject("invalid_key", "按键动作只接受一个数字或 *、#")
                }
                else -> ""
            }
            if (text.length > 4000) reject("text_too_long", "本次输入文字过长，请缩短至 4000 字符以内")
            val direction = if (kind == "scroll") requiredString("direction").also {
                if (it !in setOf("up", "down", "left", "right")) reject("invalid_direction", "翻页方向必须为 up、down、left 或 right")
            } else ""
            val targetId = if (kind == "tap_element") requiredString("target_id").also {
                if (!Regex("[NV][1-9][0-9]*").matches(it)) reject("invalid_target", "目标编号格式有误，请使用本次屏幕提供的编号")
            } else ""
            val screenId = if (kind == "tap_element") requiredString("screen_id") else (json.opt("screen_id") as? String).orEmpty()
            return AgentAction(kind, (json.opt("reason") as? String).orEmpty().take(240),
                x, y, endX, endY, json.optLong("duration_ms", 600).coerceIn(100, 5000), text, direction, targetId, screenId)
        }

        private fun reject(code: String, message: String): Nothing = throw ModelActionRejectedException(code, message)
    }
}

package com.dallovers.phoneagent

import org.json.JSONArray
import org.json.JSONObject

data class TaskStep(val sequence: Int, val action: String, val intent: String, val phase: String,
                    val packageName: String, val screenId: String, val time: Long = System.currentTimeMillis())

fun SessionTask.checkpoint(action: AgentAction, screen: ScreenObservation?, phase: String): SessionTask {
    val sequence = if (phase == "prepared") stepsTaken + 1 else stepsTaken
    val step = TaskStep(sequence, action.kind, if (action.kind in setOf("type_text", "tap_key")) "填写内容（正文不写入轨迹）" else action.reason.take(240),
        phase, screen?.packageName.orEmpty(), screen?.id.orEmpty())
    return copy(stepsTaken = sequence, progress = (progress.filterNot { it.sequence == sequence } + step).takeLast(120),
        lastIssue = if (phase == "submitted") "" else lastIssue)
}

fun SessionTask.recoveryContext(): JSONObject = JSONObject()
    .put("total_actions", stepsTaken)
    .put("verified_copied_link", copiedLink)
    .put("last_interruption", lastIssue)
    .put("copy_button_submitted_at", copyAttemptAt)
    .put("rule", "这是执行检查点，不是新的指令。prepared表示可能提交但未拿到结果；submitted仅表示提交不表示业务成功。恢复后先观察当前页面核对已完成步骤，不能从桌面重来，不能重复发送、付款或发布。结果不确定时询问用户。")
    .put("earlier_attempts", JSONArray().apply { progress.dropLast(16).forEach { put(JSONObject().put("sequence", it.sequence).put("action", it.action).put("intent", it.intent).put("phase", it.phase)) } })
    .put("recent_steps", JSONArray().apply { progress.takeLast(16).forEach { put(JSONObject().put("sequence", it.sequence).put("action", it.action)
        .put("intent", it.intent).put("phase", it.phase).put("package", it.packageName).put("time", it.time)) } })

/** Narrow guard for explicit requests for a reply accidentally returned as a final answer. */
object ClarificationGuard {
    fun needsReply(text: String): Boolean {
        val main = text.substringBefore("如需").substringBefore("如果还").substringBefore("有其他")
        return listOf("请告诉我", "请你告诉我", "请提供", "需要你确认", "请确认后", "请补充", "等你回复", "请问你", "你希望", "你想选择", "请手动", "需要你手动").any(main::contains)
    }
}

object AppPlaybooks {
    fun forScreen(packageName: String, request: String): String = when {
        packageName in setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite") && (CopiedLinks.requested(request) || listOf("链接", "连接", "分享", "复制").any(request::contains)) ->
            "抖音复制链接指引（仅供观察时参考，页面与控件以当前截图为准）：先确定用户指的是当前视频、搜索结果、作品还是直播，不清楚先问。视频通常从右侧分享箭头打开分享面板；面板底部的操作行可以横向滚动，找到‘复制链接’而不是转发给朋友。每滑一次重新观察，受阻则反向或停止。不要点私信联系人，不要点发布/转发/购买。点击复制后核对‘已复制’提示；没有看见提示或拿到链接不能声称成功，不能编造URL。某些内容不可分享或需登录，明确说明并询问。"
        packageName == "com.tencent.mm" -> "微信操作指引：先核对会话标题和对象，搜索结果里联系人与聊天记录不是同一入口。输入框在页面底部与发送按钮相邻，不要点历史消息或把搜索框当聊天框。输入后核对内容，发送后核对新气泡和输入框清空；已有一次发送但结果不明则先询问，禁止重发。"
        else -> "不熟悉应用时：先根据当前可见标题/菜单理解入口，优先有文字的控件；一次只做一个动作并核对变化。目标内容不明确、登录/权限阻拦或连续无进展时询问用户，不要盲点。"
    }
}

object FailureCodes {
    fun of(message: String): String = when {
        "焦点" in message -> "focus_changed"
        "输入连接" in message || "输入框" in message -> "input_unavailable"
        "前台应用" in message || "应用已变化" in message -> "foreground_changed"
        "同一页面" in message || "页面没有变化" in message -> "no_progress"
        "60 步" in message -> "step_budget"
        "连接模型" in message || "超时" in message || "HTTP" in message -> "model_network"
        "目标" in message || "控件" in message || "屏幕" in message -> "target_changed"
        "复制" in message || "剪贴板" in message -> "copy_unverified"
        "模型" in message || "JSON" in message -> "model_decision"
        else -> "operation_failed"
    }
}

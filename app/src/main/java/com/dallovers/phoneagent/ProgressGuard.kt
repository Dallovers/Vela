package com.dallovers.phoneagent

/** Stops revisiting the same visible state even when taps are mixed with typing or back. */
class ProgressGuard {
    private val visits = ArrayDeque<Pair<String, List<String>>>()
    private var scrollBefore: ScreenObservation? = null
    private var scrollDirection = ""
    private var scope = ""
    private val blocked = mutableMapOf<String, MutableSet<String>>()
    private var launcherScrolls = 0
    var feedback: String = ""
        private set

    private fun page(screen: ScreenObservation) = screen.contentSignature().joinToString(";") +
        screen.targets.sortedBy { it.label }.joinToString { "${it.bounds.centerX.toInt() / 32},${it.bounds.centerY.toInt() / 32}" }

    fun submitted(action: AgentAction, screen: ScreenObservation) {
        val direction = when (action.kind) {
            "scroll" -> action.direction
            "swipe" -> if (kotlin.math.abs(action.endX - action.x) > kotlin.math.abs(action.endY - action.y)) {
                if (action.endX < action.x) "right" else "left"
            } else if (action.endY < action.y) "down" else "up"
            else -> ""
        }
        scrollBefore = screen.takeIf { direction.isNotEmpty() }
        scrollDirection = direction
    }

    fun choose(action: AgentAction, screen: ScreenObservation, launcher: Boolean): AgentAction {
        val direction = when (action.kind) {
            "scroll" -> action.direction
            "swipe" -> if (kotlin.math.abs(action.endX - action.x) > kotlin.math.abs(action.endY - action.y)) {
                if (action.endX < action.x) "right" else "left"
            } else if (action.endY < action.y) "down" else "up"
            else -> return action
        }
        if (launcher && ++launcherScrolls > 12) throw InputActionUnavailableException("桌面已查找 12 次，未找到应用；请使用应用搜索或询问用户，不能继续盲翻")
        val stopped = blocked[page(screen)].orEmpty()
        if (direction !in stopped) return action
        val opposite = mapOf("left" to "right", "right" to "left", "up" to "down", "down" to "up").getValue(direction)
        if (launcher && direction in setOf("left", "right") && opposite !in stopped) {
            return AgentAction("scroll", "已到桌面边界，改向另一侧查找", direction = opposite, screenId = screen.id)
        }
        throw InputActionUnavailableException("当前页 $direction 方向已无进展，此次没有滑动。改用反方向、搜索或询问用户。")
    }

    fun observe(screen: ScreenObservation): Int {
        if (scope != screen.packageName) { blocked.clear(); launcherScrolls = 0; scope = screen.packageName }
        feedback = ""
        scrollBefore?.let { before ->
            if (before.packageName == screen.packageName && before.visiblySameAs(screen)) {
                blocked.getOrPut(page(screen)) { mutableSetOf() }.add(scrollDirection)
                feedback = "翻页后内容和位置没有变化：$scrollDirection 方向可能已到边界，禁止重复该方向；桌面查找应反向或直接打开应用。"
            } else if (before.targets.isEmpty() || screen.targets.isEmpty()) feedback = "翻页已提交，但缺少可读目标，无法确认是否移动；不能当作到头或成功。"
        }
        scrollBefore = null
        val signature = screen.targets.map { "${it.source}|${it.role}|${it.label}|${it.state}|${it.bounds.centerX.toInt() / 48}|${it.bounds.centerY.toInt() / 48}" }.sorted()
        if (signature.isEmpty()) return 0
        val key = "${screen.packageName}|${screen.keyboardVisible}" to signature
        visits.addLast(key)
        if (visits.size > 12) visits.removeFirst()
        return visits.count { it == key }
    }
}

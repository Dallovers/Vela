package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGuardTest {
    private fun screen(text: String) = ScreenObservation("s1", "practice", 1080, 2344, false,
        listOf(ScreenTarget("V1", text, "ocr", "text_region", TargetBounds(0, 0, 40, 40))))

    @Test
    fun catchesTwoPageLoopsDespiteIntermediateChanges() {
        val guard = ProgressGuard()
        repeat(4) { index ->
            assertEquals(index + 1, guard.observe(screen("搜索")))
            assertEquals(index + 1, guard.observe(screen("返回")))
        }
    }

    @Test
    fun letsAdvancingContentAndLaterRevisitsContinue() {
        val guard = ProgressGuard()
        repeat(15) { assertEquals(1, guard.observe(screen("条目$it"))) }
        assertEquals(1, guard.observe(screen("条目0")))
    }

    @Test fun launcherEdgeReversesAndBothEdgesDoNotLoopForever() {
        val guard = ProgressGuard()
        val edge = screen("应用")
        val right = AgentAction("scroll", "找应用", direction = "right", screenId = edge.id)
        guard.observe(edge)
        guard.submitted(right, edge)
        guard.observe(edge.copy(id = "s2"))
        assertTrue(guard.feedback.contains("边界"))
        val reversed = guard.choose(right, edge, launcher = true)
        assertEquals("left", reversed.direction)
        guard.submitted(reversed, edge)
        guard.observe(edge)
        try { guard.choose(right, edge, launcher = true); throw AssertionError("both edges must stop") }
        catch (_: InputActionUnavailableException) { }
    }

    @Test fun targetMovementAndUnreadablePagesAreNotMistakenForAnEdge() {
        val guard = ProgressGuard()
        val before = screen("应用")
        val swipe = AgentAction("scroll", "找", direction = "right")
        guard.observe(before); guard.submitted(swipe, before)
        val after = before.copy(targets = before.targets.map { it.copy(bounds = TargetBounds(400, 0, 440, 40)) })
        guard.observe(after)
        assertEquals(swipe, guard.choose(swipe, after, true))
        val empty = before.copy(targets = emptyList())
        guard.submitted(swipe, empty); guard.observe(empty)
        assertTrue(guard.feedback.contains("无法确认"))
        assertEquals(swipe, guard.choose(swipe, empty, true))
    }

    @Test fun nonLauncherNeverAutomaticallyReversesAndSwipeCannotBypassEdge() {
        val guard = ProgressGuard()
        val page = screen("列表")
        guard.observe(page)
        guard.submitted(AgentAction("scroll", "找", direction = "right"), page)
        guard.observe(page)
        try { guard.choose(AgentAction("swipe", "继续找", x = .8, y = .5, endX = .2, endY = .5), page, false); throw AssertionError("edge bypass") }
        catch (_: InputActionUnavailableException) { }
    }
}

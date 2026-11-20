package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentActionTest {
    @Test
    fun convertsModelCoordinatesToScreenFractions() {
        val action = AgentAction.parse("""{"action":"tap","x":250,"y":750,"reason":"定位按钮"}""")
        assertEquals(.25, action.x, .0001)
        assertEquals(.75, action.y, .0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCoordinatesOutsideTheScreenshot() {
        AgentAction.parse("""{"action":"tap","x":1001,"y":500}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownActions() {
        AgentAction.parse("""{"action":"unknown"}""")
    }

    @Test
    fun acceptsFencedJsonAndBoundsWaitDuration() {
        val action = AgentAction.parse("```json\n{\"action\":\"wait\",\"duration_ms\":9000}\n```")
        assertEquals("wait", action.kind)
        assertEquals(5000L, action.duration)
    }

    @Test
    fun acceptsNumericKeyWithoutCoordinates() {
        val action = AgentAction.parse("""{"action":"tap_key","key":"0","reason":"点击数字0"}""")
        assertEquals("tap_key", action.kind)
        assertEquals("0", action.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleKeysInOneAction() {
        AgentAction.parse("""{"action":"tap_key","key":"123"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCallButtonAsNumericKey() {
        AgentAction.parse("""{"action":"tap_key","key":"call"}""")
    }

    @Test
    fun acceptsScrollWithoutCoordinates() {
        val action = AgentAction.parse("""{"action":"scroll","direction":"down"}""")
        assertEquals("scroll", action.kind)
        assertEquals("down", action.direction)
    }

    @Test
    fun reportsMissingSwipeEndInsteadOfExecutingPartialGesture() {
        assertRejected("missing_end_y", """{"action":"swipe","x":500,"y":750,"end_x":500}""")
    }

    @Test
    fun rejectsMultipleActionsInsteadOfExecutingTheFirst() {
        assertRejected("trailing_content", """{"action":"home"}{"action":"back"}""")
    }

    @Test
    fun rejectsTruncatedJsonAsRecoverableFormatError() {
        assertRejected("invalid_json", """{"action":"tap","x":500,"y":50""")
    }

    @Test
    fun rejectsWhitespaceOnlyReplyAsRecoverableFormatError() {
        assertRejected("empty_reply", "  \n  ")
    }

    @Test
    fun rejectsAnActionArray() {
        assertRejected("non_object_reply", """[{"action":"home"}]""")
    }

    @Test
    fun acceptsUppercaseFenceAndPreservesQuotedText() {
        val action = AgentAction.parse("```JSON\n{\"action\":\"type_text\",\"text\":\"他说\\\"{你好}\\\"\"}\n```")
        assertEquals("他说\"{你好}\"", action.text)
    }

    @Test
    fun rejectsUnknownScrollDirection() {
        assertRejected("invalid_direction", """{"action":"scroll","direction":"diagonal"}""")
    }

    @Test
    fun acceptsObservedTargetWithoutCoordinates() {
        val action = AgentAction.parse("""{"action":"tap_element","target_id":"V12","screen_id":"sabc-3"}""")
        assertEquals("V12", action.targetId)
        assertEquals("sabc-3", action.screenId)
    }

    @Test
    fun rejectsTargetWithoutScreenBinding() {
        assertRejected("missing_screen_id", """{"action":"tap_element","target_id":"N2"}""")
    }

    @Test
    fun rejectsInventedTargetFormat() {
        assertRejected("invalid_target", """{"action":"tap_element","target_id":"send","screen_id":"sabc-3"}""")
    }

    private fun assertRejected(code: String, content: String) {
        try {
            AgentAction.parse(content)
            org.junit.Assert.fail("Invalid action must not execute")
        } catch (rejected: ModelActionRejectedException) {
            assertEquals(code, rejected.code)
        }
    }
}

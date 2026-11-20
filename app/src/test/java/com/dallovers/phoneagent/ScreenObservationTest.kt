package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class ScreenObservationTest {
    private val button = ScreenTarget("V1", "发送", "ocr", "text_region", TargetBounds(800, 1000, 1000, 1080))
    private val screen = ScreenObservation("s1", "practice", 1080, 2344, false, listOf(button))

    @Test
    fun matchesByContentAndBoundsInsteadOfReusedNumber() {
        val movedNumber = button.copy(id = "V8", bounds = TargetBounds(802, 1001, 1002, 1081))
        assertEquals(movedNumber, screen.findUnchangedTarget(button, screen.copy(id = "s2", targets = listOf(movedNumber))))
    }

    @Test
    fun rejectsButtonMovedByKeyboard() {
        val moved = button.copy(bounds = TargetBounds(800, 600, 1000, 680))
        assertNull(screen.findUnchangedTarget(button, screen.copy(targets = listOf(moved))))
        assertNull(screen.findUnchangedTarget(button, screen.copy(keyboardVisible = true)))
    }

    @Test
    fun rejectsOtherAppOrScreenSize() {
        assertNull(screen.findUnchangedTarget(button, screen.copy(packageName = "other")))
        assertNull(screen.findUnchangedTarget(button, screen.copy(width = 2344, height = 1080)))
    }

    @Test
    fun rejectsAmbiguousOrRelabelledTargets() {
        assertNull(screen.findUnchangedTarget(button, screen.copy(targets = listOf(button, button.copy(id = "V2")))))
        assertNull(screen.findUnchangedTarget(button, screen.copy(targets = listOf(button.copy(label = "撤回")))))
    }

    @Test
    fun contentChangeDetectionIgnoresOcrJitterButRetainsTextAndState() {
        assertEquals(screen.contentSignature(), screen.copy(targets = listOf(button.copy(id = "V4", bounds = TargetBounds(801, 999, 1001, 1079)))).contentSignature())
        assertNotEquals(screen.contentSignature(), screen.copy(targets = listOf(button.copy(label = "已发送"))).contentSignature())
        assertNotEquals(screen.contentSignature(), screen.copy(targets = listOf(button.copy(state = "checked=true"))).contentSignature())
    }
}

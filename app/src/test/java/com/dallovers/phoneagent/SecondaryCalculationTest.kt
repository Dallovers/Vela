package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class SecondaryCalculationTest {
    @Test fun resultRequiresObservedExpressionAndExactValue() {
        val task = SecondaryCalculation.parse(" 37 ＋ 58 ")!!
        assertEquals(95, task.result)
        assertTrue(task.matchesReadback(listOf("37 + 58", "＝ 95")))
        assertFalse(task.matchesReadback(listOf("5+90", "95")))
        assertFalse(task.matchesReadback(listOf("37+58", "950")))
        assertFalse(task.matchesReadback(listOf("95")))
        assertTrue(task.matchesReadback(listOf("37+58=95")))
        assertTrue(SecondaryCalculation.parse("999+999")!!.matchesReadback(listOf("999+999", "1,998")))
    }

    @Test fun rejectsUnsupportedInputBeforeSendingGestures() {
        listOf("37", "37+-58", "3+4+5", "1000+1", "0+1", "31÷3").forEach {
            assertNull(SecondaryCalculation.parse(it))
        }
    }
}

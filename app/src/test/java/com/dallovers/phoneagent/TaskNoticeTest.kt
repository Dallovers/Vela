package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNoticeTest {
    @Test fun onlyRunningToCompletedCounts() {
        assertTrue(TaskNoticeRules.justFinished(SessionStatus.RUNNING, SessionStatus.COMPLETED))
        SessionStatus.values().filter { it != SessionStatus.RUNNING }.forEach { previous ->
            assertFalse("从 $previous 变成完成不该提醒", TaskNoticeRules.justFinished(previous, SessionStatus.COMPLETED))
        }
        SessionStatus.values().filter { it != SessionStatus.COMPLETED }.forEach { next ->
            assertFalse("跑到 $next 不该当成完成", TaskNoticeRules.justFinished(SessionStatus.RUNNING, next))
        }
    }

    @Test fun watchingTheSameConversationIsNotDisturbed() {
        assertFalse(TaskNoticeRules.shouldPost(true, foreground = true, selectedSessionId = "s1", sessionId = "s1"))
        assertTrue(TaskNoticeRules.shouldPost(true, foreground = true, selectedSessionId = "s2", sessionId = "s1"))
        assertTrue(TaskNoticeRules.shouldPost(true, foreground = true, selectedSessionId = null, sessionId = "s1"))
        assertTrue(TaskNoticeRules.shouldPost(true, foreground = false, selectedSessionId = "s1", sessionId = "s1"))
    }

    @Test fun theInAppSwitchBeatsEveryOtherCondition() {
        for (visible in listOf(true, false)) {
            for (selected in listOf(null, "s1", "s2")) {
                assertFalse("关掉开关后 $visible/$selected 都不该发",
                    TaskNoticeRules.shouldPost(false, foreground = visible, selectedSessionId = selected, sessionId = "s1"))
            }
        }
    }

    @Test fun titleIsOneShortLineAndNeverEmpty() {
        assertEquals("「写周报」已完成", TaskNoticeRules.completedTitle("写周报"))
        assertEquals("「第一行 第二行」已完成", TaskNoticeRules.completedTitle("第一行\n第二行"))
        assertEquals("「这个对话」已完成", TaskNoticeRules.completedTitle("   "))
        assertEquals("「${"长".repeat(24)}」已完成", TaskNoticeRules.completedTitle("长".repeat(40)))
    }

    @Test fun bodyDiffersByMode() {
        assertEquals("点这里看回复。", TaskNoticeRules.body(ConversationMode.CHAT))
        assertEquals("点这里看执行结果。", TaskNoticeRules.body(ConversationMode.PHONE))
    }

    @Test fun interruptedTitleSaysNotDoneAndKeepsReasonToOneLine() {
        assertEquals("「写周报」没做完", TaskNoticeRules.interruptedTitle("写周报"))
        assertEquals("「这个对话」没做完", TaskNoticeRules.interruptedTitle(" "))
        assertEquals("连续点击后页面没有变化，已停止，请检查目标",
            TaskNoticeRules.interruptText("连续点击后页面没有变化，已停止，请检查目标"))
        assertEquals("第一行 第二行", TaskNoticeRules.interruptText("第一行\n第二行"))
        assertEquals("点这里看原因。", TaskNoticeRules.interruptText("   "))
        assertEquals(60, TaskNoticeRules.interruptText("长".repeat(200)).length)
    }
}

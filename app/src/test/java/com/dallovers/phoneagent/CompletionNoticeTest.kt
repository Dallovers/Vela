package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionNoticeTest {
    @Test fun onlyRunningToCompletedCounts() {
        assertTrue(CompletionNoticeRules.justFinished(SessionStatus.RUNNING, SessionStatus.COMPLETED))
        SessionStatus.values().filter { it != SessionStatus.RUNNING }.forEach { previous ->
            assertFalse("从 $previous 变成完成不该提醒", CompletionNoticeRules.justFinished(previous, SessionStatus.COMPLETED))
        }
        SessionStatus.values().filter { it != SessionStatus.COMPLETED }.forEach { next ->
            assertFalse("跑到 $next 不该当成完成", CompletionNoticeRules.justFinished(SessionStatus.RUNNING, next))
        }
    }

    @Test fun watchingTheSameConversationIsNotDisturbed() {
        assertFalse(CompletionNoticeRules.shouldPost(true, foreground = true, selectedSessionId = "s1", sessionId = "s1"))
        assertTrue(CompletionNoticeRules.shouldPost(true, foreground = true, selectedSessionId = "s2", sessionId = "s1"))
        assertTrue(CompletionNoticeRules.shouldPost(true, foreground = true, selectedSessionId = null, sessionId = "s1"))
        assertTrue(CompletionNoticeRules.shouldPost(true, foreground = false, selectedSessionId = "s1", sessionId = "s1"))
    }

    @Test fun theInAppSwitchBeatsEveryOtherCondition() {
        for (visible in listOf(true, false)) {
            for (selected in listOf(null, "s1", "s2")) {
                assertFalse("关掉开关后 $visible/$selected 都不该发",
                    CompletionNoticeRules.shouldPost(false, foreground = visible, selectedSessionId = selected, sessionId = "s1"))
            }
        }
    }

    @Test fun titleIsOneShortLineAndNeverEmpty() {
        assertEquals("「写周报」已完成", CompletionNoticeRules.title("写周报"))
        assertEquals("「第一行 第二行」已完成", CompletionNoticeRules.title("第一行\n第二行"))
        assertEquals("「这个对话」已完成", CompletionNoticeRules.title("   "))
        assertEquals("「${"长".repeat(24)}」已完成", CompletionNoticeRules.title("长".repeat(40)))
    }

    @Test fun bodyDiffersByMode() {
        assertEquals("点这里看回复。", CompletionNoticeRules.body(ConversationMode.CHAT))
        assertEquals("点这里看执行结果。", CompletionNoticeRules.body(ConversationMode.PHONE))
    }
}

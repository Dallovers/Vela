package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class SessionModelsTest {
    private val task = SessionTask(id = "task-1", title = "填写练习内容", status = TaskStatus.NEEDS_USER)
    private val question = UserQuestion(id = "question-1", taskId = task.id, prompt = "填写什么？", reason = "存在两个候选", options = listOf("甲", "乙"))
    private val session = TaskSession(id = "session-1", title = "练习", goal = "仅本地", tasks = listOf(task), status = SessionStatus.NEEDS_USER, question = question)

    @Test fun roundTripKeepsQuestionTaskAndSessionIdentity() {
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test fun restartPausesRunningTaskWithoutChangingCompletedTasks() {
        val completed = SessionTask(id = "done", title = "已完成", status = TaskStatus.COMPLETED, result = "成功")
        val running = session.copy(status = SessionStatus.RUNNING, question = null,
            tasks = listOf(completed, task.copy(status = TaskStatus.RUNNING)))
        val restored = SessionCodec.decode(SessionCodec.encode(running)).safeAfterRestart()
        assertEquals(SessionStatus.PAUSED, restored.status)
        assertEquals(completed, restored.tasks[0])
        assertEquals(TaskStatus.PAUSED, restored.tasks[1].status)
    }

    @Test fun restartPreservesUnansweredQuestionAndDoesNotRun() {
        assertEquals(session, session.safeAfterRestart())
    }

    @Test fun restoredQuestionCanBeRoutedWhileServiceIsDisconnected() {
        val restored = SessionCodec.decode(SessionCodec.encode(session)).safeAfterRestart()
        val state = AgentState(connected = false, selectedSessionId = "another-session", sessions = listOf(restored))
        assertEquals(question.id, state.pendingPhoneQuestion()?.question?.id)
        assertNull(state.copy(sessions = listOf(restored.withAnswer(question.id, "继续"))).pendingPhoneQuestion())
    }

    @Test fun staleDuplicateAndBlankAnswersDoNotChangeSession() {
        assertEquals(session, session.withAnswer("stale", "甲"))
        assertEquals(session, session.withAnswer(question.id, "  "))
        val answered = session.withAnswer(question.id, "新指令：填写测试")
        assertEquals(SessionStatus.PAUSED, answered.status)
        assertNull(answered.question)
        assertEquals(TaskStatus.PAUSED, answered.tasks.single().status)
        assertTrue(answered.summary.contains("新指令：填写测试"))
        assertEquals(answered, answered.withAnswer(question.id, "重复"))
        assertEquals(session, session.copy(id = "other", question = question.copy(id = "other-q")).withAnswer(question.id, "甲").copy(id = session.id, question = question))
    }

    @Test fun planRetainsOrderAndAssignsDistinctStableIds() {
        val tasks = parseTaskPlan("1. 观察\n2、输入\n- 核对\n\n")
        assertEquals(listOf("观察", "输入", "核对"), tasks.map { it.title })
        assertEquals(3, tasks.map { it.id }.toSet().size)
    }

    @Test fun longFreeTextAnswerIsPreservedWithinComposerLimit() {
        val answer = "回答".repeat(1800)
        val answered = session.withAnswer(question.id, answer)
        assertEquals(answer, answered.messages.last().text)
        assertTrue(answered.summary.endsWith(answer))
        assertEquals(answered, SessionCodec.decode(SessionCodec.encode(answered)))
    }

    @Test fun migratesV1WithoutInventingAssistantReplies() {
        val row = SessionCodec.encode(session).put("schema", 1)
        row.remove("messages")
        val restored = SessionCodec.decode(row)
        assertEquals(listOf("user"), restored.conversationMessages().map { it.role })
        assertEquals(session.goal, restored.conversationMessages().single().text)
    }

    @Test fun queuedMessageDoesNotRewriteOrRestartRunningTask() {
        val running = session.copy(question = null, status = SessionStatus.RUNNING,
            tasks = listOf(task.copy(status = TaskStatus.RUNNING)))
        val queued = running.queueMessage("补充要求\n保留原来的内容")
        assertEquals(running.tasks.first(), queued.tasks.first())
        assertEquals(SessionStatus.RUNNING, queued.status)
        assertEquals(2, queued.tasks.size)
        assertEquals("补充要求\n保留原来的内容", queued.tasks.last().title)
        assertEquals(queued.tasks.last().id, queued.messages.last().taskId)
    }

    @Test fun messageAndResultSurviveRestartAndStayWithinSession() {
        val queued = session.copy(question = null, status = SessionStatus.COMPLETED).queueMessage("再检查一次")
        val completed = queued.completeTask(queued.tasks.last().id, "已检查")
        assertEquals(completed, SessionCodec.decode(SessionCodec.encode(completed)))
        assertEquals("assistant", completed.messages.last().role)
        assertTrue(session.messages.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureSchemaIsRejectedRatherThanOverwritten() {
        SessionCodec.decode(SessionCodec.encode(session).put("schema", 9))
    }
}

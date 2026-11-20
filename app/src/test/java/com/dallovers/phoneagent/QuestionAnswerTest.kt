package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class QuestionAnswerTest {
    private val task = SessionTask(id = "task", title = "当前请求", status = TaskStatus.NEEDS_USER)
    private val question = UserQuestion(id = "question", taskId = task.id, prompt = "怎样继续？", reason = "需要补充")
    private val session = TaskSession(title = "测试", goal = "目标", tasks = listOf(task), status = SessionStatus.NEEDS_USER, question = question)

    @Test fun answerIsConsumedOnceAndRemainsBoundToTheOriginalTask() {
        val answered = session.withAnswer(question.id, "我自己补充的回答")
        assertNull(answered.question)
        assertEquals(SessionStatus.PAUSED, answered.status)
        assertEquals(TaskStatus.PAUSED, answered.tasks.single().status)
        assertEquals(task.id, answered.messages.last().taskId)
        assertEquals("我自己补充的回答", answered.messages.last().text)
        assertEquals(answered, answered.withAnswer(question.id, "重复点击"))
    }

    @Test fun staleButtonsCannotAnswerANewerQuestion() {
        val newer = session.copy(question = question.copy(id = "new-question"))
        assertEquals(newer, newer.withAnswer(question.id, "旧浮层回答"))
    }

    @Test fun oversizedReplyDoesNotSilentlyTruncateAndContinue() {
        assertEquals(session, session.withAnswer(question.id, "答".repeat(4001)))
    }

    @Test fun completedTaskCannotBeResumedFromAnOldQuestion() {
        val completed = session.copy(tasks = listOf(task.copy(status = TaskStatus.COMPLETED)))
        assertEquals(completed, completed.withAnswer(question.id, "旧按钮"))
    }
}

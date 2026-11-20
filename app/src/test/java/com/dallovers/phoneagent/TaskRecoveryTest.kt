package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import org.json.JSONObject

class TaskRecoveryTest {
    private val screen = ScreenObservation("screen1", "com.example.app", 1080, 2344, false, emptyList())
    @Test fun processRestartRetainsAmbiguousDispatchAndCompletedTasks() {
        val task = SessionTask(title = "执行一件事", status = TaskStatus.RUNNING, startedAt = 123)
            .checkpoint(AgentAction("tap", "点击分享"), screen, "prepared")
        val done = SessionTask(title = "已做完", status = TaskStatus.COMPLETED, result = "完成")
        val session = TaskSession(title = "恢复测试", goal = "继续", tasks = listOf(done, task), status = SessionStatus.RUNNING)
        val restored = SessionCodec.decode(SessionCodec.encode(session)).safeAfterRestart()
        assertEquals(done, restored.tasks.first())
        assertEquals(task.progress, restored.tasks.last().progress)
        assertEquals(123L, restored.tasks.last().startedAt)
        assertEquals(TaskStatus.PAUSED, restored.tasks.last().status)
        val context = JSONObject(restored.executionContext(restored.tasks.last())).getJSONObject("execution_checkpoint")
        assertEquals("prepared", context.getJSONArray("recent_steps").getJSONObject(0).getString("phase"))
    }
    @Test fun submissionReplacesPreparedAndRetainsBoundedLatestSteps() {
        var task = SessionTask(title = "任务")
        repeat(160) { task = task.checkpoint(AgentAction("wait", "等待"), screen, "prepared").checkpoint(AgentAction("wait", "等待"), screen, "submitted") }
        assertEquals(160, task.stepsTaken); assertEquals(120, task.progress.size)
        assertEquals((41..160).toList(), task.progress.map { it.sequence })
        assertTrue(task.progress.all { it.phase == "submitted" })
        assertEquals(16, task.recoveryContext().getJSONArray("recent_steps").length())
        assertEquals(104, task.recoveryContext().getJSONArray("earlier_attempts").length())
    }
    @Test fun typingBodyAndReasonAreNotPersistedInProgress() {
        val task = SessionTask(title = "填写").checkpoint(AgentAction("type_text", "写入 SecretBody", text = "SecretBody"), screen, "prepared")
        assertFalse(task.recoveryContext().toString().contains("SecretBody"))
    }
    @Test fun finalQuestionIsNormalizedToActualPendingQuestion() {
        assertTrue(DecisionParser.parse("""{"decision":"complete","result":"请告诉我你想复制哪个视频的链接？"}""") is AgentDecision.AskUser)
        assertTrue(DecisionParser.parse("""{"decision":"complete","result":"已经完成。如需其他帮助，请告诉我。"}""") is AgentDecision.Complete)
    }
    @Test fun journalSurvivesNewInstanceAndRotatesWithoutExceedingBound() {
        val directory = Files.createTempDirectory("journal").toFile()
        try {
            val journal = RunJournal(directory, maxBytes = 240, maxFiles = 3)
            repeat(30) { journal.record("action", "session", "task", "tap", "submitted", "com.example", it) }
            val restored = RunJournal(directory)
            assertTrue(restored.export().contains("submitted"))
            assertTrue(directory.listFiles()!!.size <= 3)
            restored.export().lineSequence().filter(String::isNotBlank).forEach { assertEquals("action", JSONObject(it).getString("event")) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun journalDiskFailureIsVisibleAndDoesNotDispatchAnything() {
        val root = Files.createTempDirectory("journal-failure").toFile()
        try { val bad = File(root, "file").apply { writeText("occupied") }; val journal = RunJournal(bad)
            journal.record("action"); assertNotNull(journal.error)
        } finally { root.deleteRecursively() }
    }
    @Test fun copiedLinkRequiresFreshClipboardAndExtractsOnlyURL() {
        assertEquals("https://v.douyin.com/demo030/", CopiedLinks.extract("分享文案 https://v.douyin.com/demo030/ 其他内容", 200, 100))
        assertTrue(CopiedLinks.requested("复制抖音连接"))
        assertFalse(CopiedLinks.requested("打开抖音看看"))
        assertTrue(runCatching { CopiedLinks.extract("https://old.example/", 90, 100) }.isFailure)
        assertTrue(runCatching { CopiedLinks.extract("无链接的私密文字", 200, 100) }.isFailure)
    }
    @Test fun legacySchemaMigratesWithoutInventedProgress() {
        val session = TaskSession(title = "旧会话", goal = "旧", tasks = listOf(SessionTask(title = "旧任务")))
        val json = SessionCodec.encode(session).put("schema", 4)
        json.getJSONArray("tasks").getJSONObject(0).apply { remove("progress"); remove("stepsTaken"); remove("startedAt"); remove("copiedLink") }
        val old = SessionCodec.decode(json).tasks.single()
        assertEquals(0, old.stepsTaken); assertTrue(old.progress.isEmpty())
    }
    @Test fun diagnosticCodesNeverContainPrivateErrorBody() {
        assertEquals("focus_changed", FailureCodes.of("输入焦点变化 SecretBody"))
        assertEquals("operation_failed", FailureCodes.of("SecretBody"))
    }
    @Test fun displayFixesDoubleEscapedParagraphsWithoutChangingCodeOrWindowsPaths() {
        assertEquals("结果\n\n1. 完成", narrativeText("结果\\n\\n1. 完成"))
        val path = "C:\\new\\notes.txt\\n\\n保留路径"
        assertEquals(path, narrativeText(path))
        val code = "```python\nprint('\\n\\n')\n```"
        assertEquals(code, narrativeText(code))
    }
    @Test fun verifiedCopiedLinkCannotBeOmittedByModelFinalAnswer() {
        val task = SessionTask(title = "复制链接", copiedLink = "https://v.douyin.com/demo030/")
        val session = TaskSession(title = "链接", goal = task.title, tasks = listOf(task))
        assertTrue(session.completeTask(task.id, "已经复制").messages.last().text.endsWith(task.copiedLink))
        assertEquals(task.copiedLink, session.completeTask(task.id, task.copiedLink).messages.last().text)
    }
    @Test fun interruptionCauseSurvivesRestartAndIsSuppliedOnResume() {
        val task = SessionTask(title = "复制链接", status = TaskStatus.RUNNING, lastIssue = "剪贴板不是本次任务复制的内容")
        val session = TaskSession(title = "恢复", goal = task.title, tasks = listOf(task), status = SessionStatus.RUNNING)
        val restored = SessionCodec.decode(SessionCodec.encode(session)).safeAfterRestart()
        assertEquals(task.lastIssue, JSONObject(restored.executionContext(restored.tasks.single())).getJSONObject("execution_checkpoint").getString("last_interruption"))
    }
    @Test fun copyProbeIsBoundToActualCopyButtonAttemptRatherThanOpeningApp() {
        val labelled = screen.copy(targets = listOf(ScreenTarget("N1", "分享链接", "native", "button", TargetBounds(0, 0, 60, 60))))
        assertTrue(CopiedLinks.isCopyTap(AgentAction("tap_element", "点击按钮", targetId = "N1"), labelled))
        assertFalse(CopiedLinks.isCopyTap(AgentAction("open_app", "复制链接"), labelled))
        assertFalse(CopiedLinks.isCopyTap(AgentAction("tap_element", "打开分享面板", targetId = "N2"), labelled))
    }
}

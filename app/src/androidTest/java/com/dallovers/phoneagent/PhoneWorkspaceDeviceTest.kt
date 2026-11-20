package com.dallovers.phoneagent

import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class PhoneWorkspaceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun userGrantControlsAccessToSharedDocuments() {
        if (!Environment.isExternalStorageManager()) {
            assertTrue(runCatching { PhoneStorage(context).root(PhoneFolder("primary", "Documents")) }.isFailure)
            return
        }
        val storage = PhoneStorage(context)
        val documents = storage.root(PhoneFolder("primary", "Documents"))
        val folder = File(documents, "Vela-test-${UUID.randomUUID()}")
        assertTrue(folder.mkdir())
        val source = File(folder, "note.md")
        try {
            val binding = PhoneFolder("primary", "Documents/${folder.name}")
            val files = WorkspaceFiles(storage.root(binding), binding.path) { storage.root(binding) }
            files.save(source.name, "草稿", create = true)
            assertEquals(source.name, files.entries("").single().name)
            assertEquals("草稿", files.read(source.name))
            files.save(source.name, "已更改", expected = "草稿")
            assertEquals("已更改", source.readText())
        } finally { source.delete(); folder.delete() }
    }

    @Test fun realModelCanAnswerUsingOnlyASyntheticFile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWorkspaceModel") == "true")
        val folder = File(context.cacheDir, "file-chat-${UUID.randomUUID()}")
        assertTrue(folder.mkdir())
        val source = File(folder, "demo.txt")
        val marker = UUID.randomUUID().toString().take(12)
        try {
            source.writeText("演示编号是 $marker")
            val task = SessionTask(title = "先列出当前文件夹里的文件，再读演示文本，只回答其中的演示编号。")
            val session = TaskSession(title = "文件测试", goal = "", tasks = listOf(task), mode = ConversationMode.CHAT, fileChatDirectory = "")
            val answer = ChatClient { error("无图片") }.reply(SettingsStore(context).load(), session, task,
                WorkspaceFileTools(WorkspaceFiles(folder, ""), "")) {}
            assertTrue("模型未从合成文本读出正确编号", answer.contains(marker, true))
        } finally { source.delete(); folder.delete() }
    }
}

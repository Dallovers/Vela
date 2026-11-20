package com.dallovers.phoneagent

import org.junit.Assert.*
import org.junit.Test

class WorkspaceFilesTest {
    @Test fun namesRejectTraversalAndAtomicSidecars() {
        listOf("../secret", "a/b", "a\\b", "..", ".", "a\n.txt", "file.bak", "file.new", " name").forEach { name ->
            assertTrue(name, runCatching { WorkspaceNames.validate(name) }.isFailure)
        }
        WorkspaceNames.validate("比赛笔记.md")
        WorkspaceNames.validate("notes 2026.txt")
    }
    @Test fun utf8BomIsAcceptedWithoutChangingText() {
        assertEquals("你好\nhello", WorkspaceFiles.decode("\uFEFF你好\nhello".toByteArray()))
    }
    @Test(expected = IllegalArgumentException::class) fun binaryContentCannotBeEdited() {
        WorkspaceFiles.decode(byteArrayOf(65, 0, 66))
    }
    @Test(expected = java.nio.charset.CharacterCodingException::class) fun invalidEncodingIsNotSilentlyReplaced() {
        WorkspaceFiles.decode(byteArrayOf(0xC3.toByte(), 0x28))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedTextIsRefused() {
        WorkspaceFiles.decode(ByteArray(WorkspaceFiles.MAX_BYTES + 1))
    }
}

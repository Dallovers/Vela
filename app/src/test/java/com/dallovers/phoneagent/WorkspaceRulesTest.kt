package com.dallovers.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRulesTest {
    private val local = WorkspaceInfo(WorkspacePaths.DEFAULT, "本机工作区")
    private val a = WorkspaceInfo("4b1f0f3e-1f0a-4a4e-9d38-3c1b3a2e5d10", "研报")
    private val b = WorkspaceInfo("9c2a7d41-6b55-4c8e-8f21-5d0e6a7b8c92", "代码", PhoneFolder("primary", "Projects"))
    private val rows = listOf(local, a, b)

    @Test fun namesAreTrimmedAndBounded() {
        assertEquals("研报", WorkspaceRules.cleanName("  研报  "))
        for (bad in listOf("", "   ", "a".repeat(41), "换\n行")) {
            assertTrue(bad, runCatching { WorkspaceRules.cleanName(bad) }.isFailure)
        }
    }

    @Test fun renameKeepsOrderAndOtherEntries() {
        val next = WorkspaceRules.renamed(rows, a.id, " 年报 ")
        assertEquals(listOf("本机工作区", "年报", "代码"), next.map { it.name })
        assertEquals(rows.map { it.id }, next.map { it.id })
        assertEquals(b, next[2])
    }

    @Test fun renameRejectsDefaultsMissingAndDuplicates() {
        assertTrue(runCatching { WorkspaceRules.renamed(rows, WorkspacePaths.DEFAULT, "改名") }.isFailure)
        assertTrue(runCatching { WorkspaceRules.renamed(rows, "no-such-id", "改名") }.isFailure)
        assertTrue(runCatching { WorkspaceRules.renamed(rows, a.id, "代码") }.isFailure)
    }

    @Test fun renamingToTheSameNameChangesNothing() {
        assertEquals(rows, WorkspaceRules.renamed(rows, a.id, " 研报 "))
    }

    @Test fun removalDropsOnlyThatEntry() {
        val next = WorkspaceRules.without(rows, a.id)
        assertEquals(listOf(WorkspacePaths.DEFAULT, b.id), next.map { it.id })
    }

    @Test fun defaultAndUnknownEntriesCannotBeRemoved() {
        assertTrue(runCatching { WorkspaceRules.without(rows, WorkspacePaths.DEFAULT) }.isFailure)
        assertTrue(runCatching { WorkspaceRules.without(rows, "no-such-id") }.isFailure)
    }
}

package com.dallovers.phoneagent

import java.util.TimeZone
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceBundleTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val at = 1795000000000L
    private val id = "2f1d5e2a-0000-4000-8000-000000000001"

    private fun step(n: Int, action: String, intent: String) =
        TaskStep(n, action, intent, "submitted", "com.example.app", "screen-$n", at + n * 1000L)

    private fun session(
        messages: List<ConversationMessage> = listOf(
            ConversationMessage("m1", "user", "帮我看看屏幕", "t1"),
            ConversationMessage("m2", "assistant", "页面是设置页", "t1"),
        ),
        progress: List<TaskStep> = listOf(step(1, "tap", "点击搜索框"), step(2, "type_text", "填写内容（正文不写入轨迹）")),
        attachments: List<TextAttachment> = emptyList(),
    ) = TaskSession(
        id = id, title = "看屏幕/截图 说明", goal = "看屏幕",
        tasks = listOf(SessionTask(id = "t1", title = "看屏幕说明", completion = "看屏幕说明",
            status = TaskStatus.COMPLETED, result = "页面是设置页", progress = progress,
            stepsTaken = progress.size, startedAt = at, attachments = attachments)),
        status = SessionStatus.COMPLETED, messages = messages, updatedAt = at,
        workspaceId = WorkspacePaths.DEFAULT,
    )

    private fun journal() = listOf(
        """{"time":1,"event":"action","session":"$id","task":"t1","action":"tap","phase":"submitted","package":"com.example.app","count":0}""",
        """{"time":2,"event":"decision","session":"another-session","task":"t9","action":"","phase":"act","package":"","count":0}""",
        """{"time":3,"event":"action_rejected","session":"$id","task":"t1","action":"tap","phase":"rejected","package":"com.example.app","count":0}""",
    ).joinToString("\n")

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }

    @Test fun bundleHasManifestFirstThenTheReadableParts() {
        val entries = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc)
        assertEquals(listOf("manifest.json", "conversation.md", "tasks.json", "trace/1-看屏幕说明.csv", "journal.jsonl", "README.txt"),
            entries.map { it.name })
    }

    @Test fun journalKeepsOnlyTheSameConversation() {
        val entry = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc).first { it.name == "journal.jsonl" }
        assertTrue(entry.text.contains("\"time\":1"))
        assertTrue(entry.text.contains("\"time\":3"))
        assertFalse(entry.text.contains("another-session"))
    }

    @Test fun journalLimitKeepsNewestLines() {
        val slice = EvidenceBundle.journalSlice(journal(), id, limit = 1)
        assertEquals(2, slice.matched)
        assertEquals(1, slice.lines.size)
        assertTrue(slice.lines.single().contains("\"time\":3"))
    }

    @Test fun journalBeyondTheCapIsFlaggedInManifest() {
        val many = (1..900).joinToString("\n") { n ->
            """{"time":$n,"event":"action","session":"$id","task":"t1","action":"tap","phase":"submitted","package":"com.example.app","count":0}"""
        }
        val entries = EvidenceBundle.build(session(), many, "gpt-x", at, utc)
        val journalEntry = entries.first { it.name == "journal.jsonl" }
        assertEquals(EvidenceBundle.MAX_JOURNAL_LINES, journalEntry.text.lines().size)
        assertTrue(journalEntry.text.contains("\"time\":900"))
        assertFalse(journalEntry.text.contains("\"time\":1,"))
        assertTrue(strings(JSONObject(entries.first().text).getJSONArray("truncated")).contains("journal.jsonl"))
    }

    @Test fun traceEscapesCommasQuotesAndNewlines() {
        val tricky = step(7, "tap", "点\"搜索\",然后\n再点一次")
        val csv = EvidenceBundle.traceCsv(session(progress = listOf(tricky)).tasks.single(), utc)
        assertTrue(csv.startsWith("sequence,action,intent,phase,package,screen,time\r\n"))
        assertTrue(csv.contains("7,tap,\"点\"\"搜索\"\",然后\n再点一次\",submitted,com.example.app,screen-7,"))
        assertEquals("\"a,b\"", EvidenceBundle.cell("a,b"))
        assertEquals("\"a\"\"b\"", EvidenceBundle.cell("a\"b"))
    }

    @Test fun clippedEntryStopsAtAWholeCharacterAndSaysSo() {
        val long = "汉".repeat(EvidenceBundle.MAX_ENTRY_BYTES)
        val (entry, cut) = EvidenceBundle.clip("big.md", long)
        assertTrue(cut)
        assertTrue(entry.bytes().size <= EvidenceBundle.MAX_ENTRY_BYTES + EvidenceBundle.CUT.length * 3 + 1)
        assertTrue(entry.text.endsWith(EvidenceBundle.CUT))
        assertFalse(entry.text.contains('\uFFFD'))
    }

    @Test fun slugAndFileNameNeverCarryPathCharacters() {
        assertEquals("a-b-c", EvidenceBundle.slug("a/b:c", id))
        assertEquals("2f1d5e2a", EvidenceBundle.slug("  ", id))
        val name = EvidenceBundle.fileName("看屏幕/截图 说明", id, at, utc)
        assertTrue(name, name.matches(Regex("证据包-看屏幕-截图-说明-\\d{8}-\\d{4}\\.zip")))
    }

    @Test fun readmeSurvivesAndSaysWhatWasOmitted() {
        val entries = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc, totalBudgetBytes = 64 * 1024 + 300)
        val readme = entries.first { it.name == "README.txt" }.text
        assertTrue(readme, readme.contains("这个包不完整"))
        assertTrue(readme, readme.contains("省略 3 项"))
        assertTrue(readme, readme.contains("tasks.json"))
        val normal = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc).first { it.name == "README.txt" }.text
        assertFalse(normal, normal.contains("这个包不完整"))
    }

    @Test fun exportReportsHowManyPartsWereOmitted() {
        val export = EvidenceBundle.export(session(), journal(), "gpt-x", at, utc, totalBudgetBytes = 64 * 1024 + 300)
        assertEquals(3, export.omitted)
        assertEquals(listOf("tasks.json", "trace/1-看屏幕说明.csv", "journal.jsonl"), export.dropped)
        assertTrue(export.bytes.isNotEmpty())
        assertEquals(0, EvidenceBundle.export(session(), journal(), "gpt-x", at, utc).omitted)
    }

    @Test fun oversizedBundlesDropLowPriorityPartsAndRecordIt() {
        val entries = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc, totalBudgetBytes = 64 * 1024 + 300)
        assertEquals(listOf("manifest.json", "conversation.md", "README.txt"), entries.map { it.name })
        val manifest = JSONObject(entries.first().text)
        assertEquals(listOf("tasks.json", "trace/1-看屏幕说明.csv", "journal.jsonl"), strings(manifest.getJSONArray("dropped")))
        assertEquals(listOf("conversation.md", "README.txt"), objects(manifest.getJSONArray("files")).map { it.getString("name") })
    }

    @Test fun manifestListsEveryKeptFileWithItsHash() {
        val entries = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc)
        val manifest = JSONObject(entries.first().text)
        val files = objects(manifest.getJSONArray("files"))
        assertEquals(entries.drop(1).map { it.name }, files.map { it.getString("name") })
        entries.drop(1).forEachIndexed { index, entry ->
            assertEquals(entry.bytes().size.toLong(), files[index].getLong("bytes"))
            assertEquals(entry.sha256(), files[index].getString("sha256"))
            assertTrue(files[index].getString("sha256").matches(Regex("[a-f0-9]{64}")))
        }
        assertEquals("gpt-x", manifest.getString("model"))
        assertEquals(entries.size.toLong(), manifest.getJSONObject("counts").getLong("files"))
    }

    @Test fun attachmentBodyIsNeverCopiedIntoTheBundle() {
        val file = TextAttachment.snapshot(WorkspacePaths.DEFAULT, "note.txt", "SECRET-ATTACHMENT-BODY")
        val entries = EvidenceBundle.build(session(attachments = listOf(file)), journal(), "gpt-x", at, utc)
        assertTrue(entries.none { it.text.contains("SECRET-ATTACHMENT-BODY") })
        val tasks = JSONObject(entries.first { it.name == "tasks.json" }.text)
        assertFalse(tasks.getBoolean("attachmentsCopied"))
        val listed = tasks.getJSONArray("tasks").getJSONObject(0).getJSONArray("attachments").getJSONObject(0)
        assertEquals("note.txt", listed.getString("name"))
        assertEquals(file.sha256, listed.getString("sha256"))
    }

    @Test fun zipRoundTripsAndSameInputGivesSameBytes() {
        val entries = EvidenceBundle.build(session(), journal(), "gpt-x", at, utc)
        val bytes = EvidenceBundle.zip(entries)
        val readBack = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                readBack[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(entries.associate { it.name to it.text }, readBack)
        assertArrayEquals(bytes, EvidenceBundle.zip(EvidenceBundle.build(session(), journal(), "gpt-x", at, utc)))
    }
}

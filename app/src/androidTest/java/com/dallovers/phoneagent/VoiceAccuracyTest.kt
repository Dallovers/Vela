package com.dallovers.phoneagent

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/** Fixed synthetic speech, never private microphone recordings. */
class VoiceAccuracyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets

    @Test fun microphoneFinishAndCancelDeliverAtMostOnce() = runBlocking {
        assertEquals("Grant microphone through the app's permission dialog before this device test",
            android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var delivered = 0
        var rejected = 0
        val events = mutableListOf<String>()
        val recorder = withContext(Dispatchers.Main) {
            ComposerDictation(context, scope) { event, _ -> events.add(event) }.also {
                it.start({ delivered++ }, { rejected++ })
            }
        }
        try {
            withTimeout(15000) {
                while (withContext(Dispatchers.Main) { recorder.phase != "正在听…" }) delay(50)
            }
            delay(1700)
            withContext(Dispatchers.Main) { recorder.stop() }
            withTimeout(20000) {
                while (withContext(Dispatchers.Main) { recorder.active }) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertEquals(1, delivered + rejected)
                assertTrue(recorder.elapsedSeconds >= 1)
                assertTrue(events.contains("voice_decoded"))
                recorder.start({ delivered++ }, { rejected++ })
            }
            delay(500)
            withContext(Dispatchers.Main) { recorder.cancel() }
            delay(1000)
            withContext(Dispatchers.Main) {
                assertFalse(recorder.active)
                assertTrue(events.contains("voice_cancelled"))
                assertEquals(1, delivered + rejected)
            }
        } finally {
            withContext(Dispatchers.Main) { recorder.cancel(); activity.finish() }
            scope.cancel()
        }
    }

    @Test fun taskDictationCorpusAndSilence() = runBlocking {
        val cases = JSONArray(assets.open("voice-corpus/corpus.json").bufferedReader().use { it.readText() })
        val baseline = legacyModel()
        val rows = JSONArray()
        var newErrors = 0
        var oldErrors = 0
        var characters = 0
        try {
            VoiceRecognition.open(context).use { model ->
                assertEquals("", model.decode(FloatArray(16000)))
                for (index in 0 until cases.length()) {
                    val item = cases.getJSONObject(index)
                    val pcm = assets.open("voice-corpus/" + item.getString("file")).use { it.readBytes() }
                    val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = FloatArray(shorts.remaining()) { shorts.get() / 32768f }
                    val started = System.nanoTime()
                    val actual = model.decode(samples)
                    val millis = (System.nanoTime() - started) / 1_000_000
                    val expected = normalized(item.getString("expected"))
                    val errors = distance(expected, normalized(actual))
                    val previous = baseline?.let { legacy(it, pcm) }
                    val previousErrors = previous?.let { distance(expected, normalized(it)) }
                    newErrors += errors
                    oldErrors += previousErrors ?: 0
                    characters += expected.length
                    rows.put(JSONObject().put("id", item.getString("id")).put("expected", item.getString("expected"))
                        .put("sensevoice", actual).put("previous", previous).put("errors", errors).put("previousErrors", previousErrors)
                        .put("decodeMs", millis).put("audioMs", pcm.size / 32))
                }
            }
        } finally { baseline?.close() }
        val report = JSONObject().put("cases", rows).put("characters", characters).put("sensevoiceErrors", newErrors)
            .put("previousErrors", if (baseline == null) JSONObject.NULL else oldErrors)
        File(context.cacheDir, "voice-accuracy.json").writeText(report.toString(2))
        assertTrue("Fixed corpus CER: $newErrors / $characters", newErrors.toDouble() / characters < .20)
        if (baseline != null) assertTrue("New $newErrors, previous $oldErrors", newErrors < oldErrors)
    }

    @Test fun captureBufferKeepsOrderAndExactLimit() {
        val buffer = VoiceSamples()
        assertTrue(buffer.append(shortArrayOf(1000, -2000, 32767), 3))
        val earlier = buffer.snapshot()
        assertEquals(1000f / 32768, earlier[0])
        assertEquals(-2000f / 32768, earlier[1])
        assertTrue(buffer.append(shortArrayOf(3000), 1))
        assertEquals(3, earlier.size)
        val capacity = VoiceRecognition.SAMPLE_RATE * VoiceRecognition.MAX_SECONDS
        assertFalse(buffer.append(ShortArray(capacity) { 7 }, capacity))
        assertEquals(capacity, buffer.size())
        assertEquals(3000f / 32768, buffer.snapshot()[3])
    }

    private fun legacyModel(): Model? {
        if (!assets.list("").orEmpty().contains("baseline-vosk.zip")) return null
        val root = File(context.cacheDir, "baseline-vosk").apply { mkdirs() }
        ZipInputStream(assets.open("baseline-vosk.zip")).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val destination = File(root, entry.name.removePrefix("vosk-model-small-cn-0.22/"))
                require(destination.canonicalPath.startsWith(root.canonicalPath + File.separator))
                destination.parentFile?.mkdirs()
                destination.outputStream().use { zip.copyTo(it) }
            }
        }
        return Model(root.absolutePath)
    }

    private fun legacy(model: Model, pcm: ByteArray): String = Recognizer(model, 16000f).use { recognizer ->
        buildString {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + 3200, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                if (recognizer.acceptWaveForm(chunk, chunk.size)) append(JSONObject(recognizer.result).optString("text"))
                offset = end
            }
            append(JSONObject(recognizer.finalResult).optString("text"))
        }
    }

    private fun normalized(text: String): String = text.lowercase().map {
        val digit = "零一二三四五六七八九".indexOf(it)
        if (digit >= 0) ('0'.code + digit).toChar() else it
    }.filter { it.isLetterOrDigit() }.joinToString("")

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row[b.length]
    }
}

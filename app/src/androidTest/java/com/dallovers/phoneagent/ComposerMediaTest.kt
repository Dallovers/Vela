package com.dallovers.phoneagent

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ComposerMediaTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val store = DeviceAttachments(context)
    private val dir = File(context.cacheDir, "camera").apply { mkdirs() }

    private fun attach(file: File): TextAttachment {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".camera", file)
        return store.import(uri, "local")
    }

    @Test fun snapshotsFromContentUrisReachModelInOrderAndSurviveSessionRestore() {
        val photo = File(dir, "fixture032.png")
        val bitmap = Bitmap.createBitmap(140, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val image = attach(photo)
        photo.delete()
        val pdf = File(dir, "fixture032.pdf")
        val doc = PdfDocument()
        try {
            repeat(2) { index ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(200, 260, index + 1).create())
                page.canvas.drawColor(if (index == 0) Color.RED else Color.BLUE)
                doc.finishPage(page)
            }
            pdf.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        val document = attach(pdf)
        val word = File(dir, "fixture032.docx")
        ZipOutputStream(word.outputStream()).use {
            it.putNextEntry(ZipEntry("word/document.xml"))
            it.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>山海032</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            it.closeEntry()
        }
        val txt = File(dir, "fixture032.txt").apply { writeText("松树032") }
        val attachments = listOf(image, document, attach(word), attach(txt))
        val session = TaskSession(title = "fixture", goal = "", tasks = emptyList()).queueMessage("描述附件", attachments)
        val restored = SessionCodec.decode(SessionCodec.encode(session))
        val body = ChatProtocol.messages(restored, restored.tasks.single(), store::dataUrl)
        val parts = body.getJSONObject(body.length()-1).getJSONArray("content")
        assertEquals(7, parts.length())
        assertTrue(parts.getJSONObject(0).getString("text").contains("山海032"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("松树032"))
        for (i in listOf(2,4,6)) assertTrue(parts.getJSONObject(i).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
        assertNotEquals(document.pages[0], document.pages[1])
        val invalid = File(dir, "fixture032.bin").apply { writeBytes(byteArrayOf(0,1,2)) }
        assertTrue(runCatching { attach(invalid) }.isFailure)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"已收到附件"}}]}"""))
            val settings = ModelSettings(server.url("/v1").toString(), "fixture", "local-test")
            val reply = runBlocking { ChatClient(store::dataUrl).reply(settings, restored, restored.tasks.single()) {} }
            assertEquals("已收到附件", reply)
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals(parts.toString(), messages.getJSONObject(messages.length()-1).getJSONArray("content").toString())
        }
    }

    @Test fun bundledRecognizerTranscribesMandarinPcmOnDevice() = runBlocking {
        val pcm = instrument.context.assets.open("mandarin-test.pcm").use { it.readBytes() }
        VoiceRecognition.open(context).use { recognizer ->
            val samples = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val actual = recognizer.decode(FloatArray(samples.remaining()) { samples.get() / 32768f }).replace(" ", "")
            assertTrue("Recognition was: $actual", actual.contains("今天天气") || actual.contains("天气很好"))
        }
    }
}

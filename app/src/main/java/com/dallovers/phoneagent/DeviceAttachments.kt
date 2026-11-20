package com.dallovers.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/** Only the chosen URI is opened. Queued tasks use private, content-addressed snapshots. */
internal class DeviceAttachments(private val context: Context) {
    private val root = File(context.filesDir, "attachment-images")
    private val documents = File(context.filesDir, "attachment-documents")
    private fun file(digest: String): File {
        require(digest.matches(Regex("[a-f0-9]{64}")))
        return File(root, digest)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun bytes(digest: String): ByteArray = file(digest).inputStream().use { it.limited(2 * 1024 * 1024) }.also {
        require(hash(it) == digest) { "附件已损坏，请重新选择" }
    }
    fun dataUrl(digest: String) = "data:image/jpeg;base64," + Base64.encodeToString(bytes(digest), Base64.NO_WRAP)

    fun prune(live: Set<String>, liveDocuments: Set<String> = emptySet()) {
        documents.listFiles().orEmpty().filter { it.name !in liveDocuments }.forEach { it.delete() }
        root.listFiles().orEmpty().filter { it.name !in live }.forEach { it.delete() }
    }

    internal fun store(bitmap: Bitmap): String {
        val buffer = ByteArrayOutputStream()
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 84, buffer)) } finally { bitmap.recycle() }
        val bytes = buffer.toByteArray()
        require(bytes.size <= 2 * 1024 * 1024) { "图片内容过大，请裁剪后再试" }
        val digest = hash(bytes)
        root.mkdirs()
        val dest = file(digest)
        if (!dest.exists()) {
            val tmp = File.createTempFile("image-", ".part", root)
            try {
                tmp.outputStream().use { it.write(bytes); it.fd.sync() }
                check(tmp.renameTo(dest) || dest.isFile) { "图片保存失败" }
            } finally { tmp.delete() }
        }
        return digest
    }

    fun import(uri: Uri, workspace: String, preferredName: String? = null): TextAttachment {
        val resolver = context.contentResolver
        val name = preferredName ?: resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "附件"
        WorkspaceNames.validate(name)
        val mime = resolver.getType(uri).orEmpty()
        val suffix = name.substringAfterLast('.', "").lowercase()
        val source = File.createTempFile("attachment-", ".part", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buf = ByteArray(16384)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= WorkspaceFiles.MAX_READ_BYTES) { "单个附件最多 50 MB" }
                        output.write(buf, 0, n)
                    }
                }
            } ?: error("无法打开所选文件")
            val pages = when {
                mime.startsWith("image/") || suffix in setOf("png","jpg","jpeg","webp","heic","heif","gif","avif") -> {
                    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        require(w.toLong() * h <= 120_000_000) { "图片尺寸过大" }
                        val scale = minOf(1f, 1600f / maxOf(w, h))
                        decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    listOf(store(bitmap))
                }
                mime == "application/pdf" || suffix == "pdf" -> return keepDocument(source, workspace, name, DocumentReader.pdfInfo(source).getInt("total_pages"))
                else -> {
                    val text = runCatching {
                        if (suffix == "docx" || mime.endsWith("wordprocessingml.document")) wordText(source)
                        else source.inputStream().use { WorkspaceFiles.decode(it.limited(TextAttachment.MAX_BYTES)) }
                    }.getOrNull()
                    return if (text != null) TextAttachment.snapshot(workspace, name, text) else keepDocument(source, workspace, name)
                }
            }
            return TextAttachment(workspace, name, "", TextAttachment.hash(pages.joinToString("\n")), pages)
        } finally { source.delete() }
    }

    private fun keepDocument(source: File, workspace: String, name: String, pages: Int = 0): TextAttachment {
        val digest = source.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16384)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        documents.mkdirs()
        val target = File(documents, digest)
        check(target.isFile || source.renameTo(target)) { "附件原件保存失败" }
        return TextAttachment(workspace, name, "", digest, document = digest, pageCount = pages)
    }

    fun document(digest: String): File {
        require(digest.matches(Regex("[a-f0-9]{64}")))
        return File(documents, digest).also { check(it.isFile && it.length() <= WorkspaceFiles.MAX_READ_BYTES) { "附件原件无法读取，请重新添加" } }
    }

    private fun wordText(source: File): String = ZipFile(source).use { archive ->
        val entry = archive.getEntry("word/document.xml") ?: error("未找到 Word 正文")
        val xml = archive.getInputStream(entry).use { it.limited(2 * 1024 * 1024) }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(ByteArrayInputStream(xml), null)
        }
        buildString {
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                require(parser.eventType != XmlPullParser.DOCDECL) { "这个 Word 文档无法读取" }
                val word = parser.namespace == "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                if (word && parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                    "t" -> append(parser.nextText())
                    "tab" -> append('\t')
                    "br" -> append('\n')
                }
                if (word && parser.eventType == XmlPullParser.END_TAG && parser.name == "p") append('\n')
                require(length <= TextAttachment.MAX_BYTES) { "文档正文超过 64 KB" }
            }
        }.trimEnd().also { require(it.isNotBlank()) { "Word 文档没有可读取的正文" } }
    }

    private fun InputStream.limited(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            require(out.size() + n <= max) { "文件内容超过大小限制" }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

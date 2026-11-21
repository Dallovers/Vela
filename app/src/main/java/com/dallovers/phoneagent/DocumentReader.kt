package com.dallovers.phoneagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Xml
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** All parser work runs on the caller's I/O dispatcher. Only requested pages are rendered. */
internal class DocumentReader(private val context: Context, private val media: DeviceAttachments) {
    fun read(file: File, name: String, args: JSONObject): JSONObject {
        require(file.isFile && file.length() <= WorkspaceFiles.MAX_READ_BYTES) { "文件须在 50 MB 以内" }
        val offset = args.optInt("offset", 0)
        require(offset >= 0)
        val header = ByteArray(8).also { buffer -> file.inputStream().use { it.read(buffer) } }
        val suffix = name.substringAfterLast('.', "").lowercase()
        val result = when {
            header.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) -> pdf(file, offset)
            header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() -> archive(file, offset, args.optString("entry"))
            suffix in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif", "bmp") -> image(file)
            suffix in setOf("mp3", "m4a", "wav", "ogg", "flac", "mp4", "mov", "mkv", "webm", "3gp") -> mediaInfo(file)
            else -> {
                val chunk = runCatching { WorkspaceFiles(file.parentFile!!, "").readChunk(file.name, offset) }.getOrNull()
                if (chunk != null) JSONObject().put("format", "text").put("offset_unit", "UTF-16字符")
                    .put("text", chunk.text).apply { chunk.nextOffset?.let { put("next_offset", it) } }
                else JSONObject().put("format", suffix.ifBlank { "binary" }).put("status", "unsupported")
                    .put("reason", "该格式、编码或加密方式暂未支持正文解析，可报告文件属性，不能据此猜测正文")
            }
        }
        return result.put("bytes", file.length()).put("name", name)
    }

    private fun image(file: File): JSONObject {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            require(info.size.width.toLong() * info.size.height <= 120_000_000) { "图片尺寸过大" }
            val scale = minOf(1f, 1800f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            val text = ocr(bitmap)
            val digest = media.store(bitmap)
            JSONObject().put("format", "image").put("text", text.take(12000))
                .put("page_images", JSONArray().put(JSONObject().put("page", 1).put("digest", digest)))
        } finally { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private fun ocr(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return try { Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text }
        finally { recognizer.close() }
    }

    private fun pdf(file: File, offset: Int): JSONObject = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            require(offset < renderer.pageCount) { "PDF 页码超出范围" }
            val rows = JSONArray()
            val images = JSONArray()
            val end = minOf(offset.toLong() + 2, renderer.pageCount.toLong()).toInt()
            for (index in offset until end) renderer.openPage(index).use { page ->
                val scale = 1800f / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                    (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val native = if (Build.VERSION.SDK_INT >= 35) page.textContents.joinToString("\n") { it.text } else ""
                    val text = if (native.isBlank()) ocr(bitmap) else native
                    rows.put(JSONObject().put("page", index + 1).put("text", text.take(12000))
                        .put("text_truncated", text.length > 12000).put("source", if (native.isBlank()) "ocr" else "pdf_text"))
                    images.put(JSONObject().put("page", index + 1).put("digest", media.store(bitmap)))
                } finally { if (!bitmap.isRecycled) bitmap.recycle() }
            }
            JSONObject().put("format", "pdf").put("total_pages", renderer.pageCount).put("pages", rows)
                .put("page_images", images).put("offset_unit", "从0开始的页序号")
                .apply { if (end < renderer.pageCount) put("next_offset", end) }
        }
    }

    private fun archive(file: File, offset: Int, selected: String): JSONObject = ZipFile(file).use { zip ->
        if (selected.isNotEmpty()) {
            require(selected.split('/').all { it.isNotEmpty() && it != ".." && it != "." } && '\\' !in selected)
            val entry = zip.getEntry(selected) ?: error("压缩包内没有这个条目")
            require(!entry.isDirectory)
            val temporary = File.createTempFile("document-", ".part", context.cacheDir)
            try {
                zip.getInputStream(entry).use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= WorkspaceFiles.MAX_READ_BYTES) { "压缩条目展开后超过 50 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                read(temporary, selected, JSONObject().put("offset", offset)).put("archive_entry", selected)
            } finally { temporary.delete() }
        } else when {
            zip.getEntry("word/document.xml") != null -> chunk("docx", xmlText(zip, "word/document.xml"), offset)
            zip.getEntry("xl/workbook.xml") != null -> chunk("xlsx", spreadsheet(zip), offset)
            zip.getEntry("ppt/presentation.xml") != null -> {
                val slides = officeParts(zip, "ppt/presentation.xml", "sldId")
                chunk("pptx", boundedJoin(slides.asSequence().mapIndexed { index, part -> "第 ${index + 1} 张幻灯片\n" + xmlText(zip, part.second) }), offset)
            }
            else -> {
                val entries = zip.entries().asSequence().drop(offset).take(41).toList()
                JSONObject().put("format", "archive").put("entries", JSONArray().apply {
                    entries.take(40).forEach { put(JSONObject().put("name", it.name).put("bytes", it.size).put("directory", it.isDirectory)) }
                }).put("offset_unit", "条目序号").put("hint", "需要读取内部文件时，使用同一路径并在 entry 填写准确条目名")
                    .apply { if (entries.size > 40) put("next_offset", offset + 40) }
            }
        }
    }

    private fun spreadsheet(zip: ZipFile): String {
        val shared = mutableListOf<String>()
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            val parser = xml(zip, "xl/sharedStrings.xml")
            var value = StringBuilder()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "si") value = StringBuilder()
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "t") value.append(parser.nextText())
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "si") shared.add(value.toString())
            }
        }
        val sheets = officeParts(zip, "xl/workbook.xml", "sheet")
        return boundedJoin(sheets.asSequence().map { (label, path) ->
            val parser = xml(zip, path)
            buildString {
                append("工作表 ").append(label).append(" · ").append(path).append("\n数值使用文件内存储值，公式显示缓存结果。\n")
                var type = ""
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                        "c" -> { type = parser.getAttributeValue(null, "t").orEmpty(); append(parser.getAttributeValue(null, "r")).append("=") }
                        "v" -> { val value = parser.nextText(); append(if (type == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value); append('\t') }
                        "t" -> append(parser.nextText()).append('\t')
                        "f" -> append("公式(").append(parser.nextText()).append(") 缓存值=")
                    }
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "row") append('\n')
                    require(length <= MAX_EXTRACTED) { "表格展开内容超过处理范围" }
                }
            }
        })
    }

    private fun officeParts(zip: ZipFile, manifest: String, tag: String): List<Pair<String, String>> {
        val directory = manifest.substringBeforeLast('/')
        val relations = xml(zip, "$directory/_rels/${manifest.substringAfterLast('/')}.rels")
        val targets = mutableMapOf<String, String>()
        while (relations.next() != XmlPullParser.END_DOCUMENT) {
            if (relations.eventType == XmlPullParser.START_TAG && relations.name == "Relationship" &&
                relations.getAttributeValue(null, "TargetMode") != "External") {
                targets[relations.getAttributeValue(null, "Id")] = relations.getAttributeValue(null, "Target")
            }
        }
        val parser = xml(zip, manifest)
        val parts = mutableListOf<Pair<String, String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == tag) {
                val id = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                val target = targets[id] ?: error("文档页面关系缺失")
                val path = if (target.startsWith('/')) target.removePrefix("/") else "$directory/$target"
                validateRelativePath(path)
                parts.add((parser.getAttributeValue(null, "name") ?: (parts.size + 1).toString()) to path)
                require(parts.size <= 2000) { "文档页面过多" }
            }
        }
        return parts
    }

    private fun xml(zip: ZipFile, name: String): XmlPullParser {
        val entry = zip.getEntry(name) ?: error("文档结构缺失")
        val bytes = zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_EXTRACTED) { "文档展开内容过大" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        require(bytes.size <= MAX_EXTRACTED) { "文档展开内容过大" }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(bytes.inputStream(), null)
        }
    }

    private fun xmlText(zip: ZipFile, name: String): String {
        val parser = xml(zip, name)
        return buildString {
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                require(parser.eventType != XmlPullParser.DOCDECL) { "文档包含无法处理的声明" }
                if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                    "t" -> append(parser.nextText())
                    "tab" -> append('\t')
                    "br" -> append('\n')
                }
                if (parser.eventType == XmlPullParser.END_TAG && parser.name == "p") append('\n')
                require(length <= MAX_EXTRACTED) { "文档展开内容过大" }
            }
        }
    }

    private fun boundedJoin(parts: Sequence<String>): String = buildString {
        for (part in parts) {
            require(length.toLong() + part.length + 2 <= MAX_EXTRACTED) { "文档展开内容过大" }
            append(part).append("\n\n")
        }
    }

    private fun chunk(format: String, text: String, offset: Int): JSONObject {
        val start = offset.coerceAtMost(text.length)
        val end = minOf(start + 6000, text.length)
        return JSONObject().put("format", format).put("offset_unit", "UTF-16字符")
            .put("text", text.substring(start, end)).apply { if (end < text.length) put("next_offset", end) }
    }

    private fun mediaInfo(file: File): JSONObject {
        val result = JSONObject().put("format", "audio_video").put("status", "metadata_only")
            .put("hint", "已读取媒体属性，尚未转写声音或分析视频画面")
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.path)
            for ((label, key) in listOf("duration_ms" to MediaMetadataRetriever.METADATA_KEY_DURATION,
                "width" to MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "height" to MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                "title" to MediaMetadataRetriever.METADATA_KEY_TITLE)) reader.extractMetadata(key)?.let { result.put(label, it) }
            result
        } finally { reader.release() }
    }

    companion object {
        private const val MAX_EXTRACTED = 16 * 1024 * 1024
        fun pdfInfo(file: File): JSONObject = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { JSONObject().put("total_pages", it.pageCount) }
        }
    }
}

/** Selected original files are immutable snapshots, including files queued for a later request. */
internal class AttachedDocumentTools(private val delegate: FileTools, private val attachments: List<TextAttachment>,
    private val media: DeviceAttachments, private val reader: DocumentReader) : FileTools {
    override val description = delegate.description + "\n所选完整文件可用 read_file 分段解析，路径为 " +
        attachments.joinToString { "@attachments/${it.name}" }

    override fun execute(name: String, args: JSONObject): JSONObject {
        val path = args.getString("path")
        if (!path.startsWith("@attachments/")) return delegate.execute(name, args)
        require(name == "read_file") { "完整附件使用 read_file" }
        val attachment = attachments.lastOrNull { it.name == path.removePrefix("@attachments/") } ?: error("当前任务未选择该附件")
        return reader.read(media.document(attachment.document), attachment.name, args).put("path", path)
    }
}

internal object FileVisuals {
    fun append(content: JSONArray, result: JSONObject, imageUrl: (String) -> String) {
        val images = result.optJSONArray("page_images") ?: return
        for (index in 0 until minOf(images.length(), 2)) {
            val page = images.getJSONObject(index)
            content.put(JSONObject().put("type", "text").put("text", "文件资料 ${result.optString("path")} 第 ${page.getInt("page")} 页"))
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl(page.getString("digest")))))
        }
    }
}

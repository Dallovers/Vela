package com.dallovers.phoneagent

import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class TextChunk(val text: String, val nextOffset: Int?, val totalBytes: Long)

data class WorkspaceEntry(val name: String, val directory: Boolean, val size: Long)

class WorkspaceFiles(private val root: File, private val phoneBase: String? = null,
                     private val checkAccess: () -> Unit = {}) {
    init {
        if (phoneBase == null) check(root.isDirectory || root.mkdirs()) { "无法创建本机工作区" }
    }
    private fun allowed(path: String): Boolean = phoneBase == null ||
        phonePathAllowed(listOf(phoneBase, path).filter(String::isNotEmpty).joinToString("/"))

    private fun resolve(path: String): File {
        checkAccess()
        require(allowed(path)) { "这个目录受系统保护" }
        if (phoneBase == null) require(path.split('/').none { it.endsWith(".new") || it.endsWith(".bak") }) { "工作区保留文件不可访问" }
        return checkedPath(root, path)
    }

    fun entries(directory: String): List<WorkspaceEntry> {
        val files = resolve(directory).listFiles() ?: error("文件夹不存在或没有读取权限")
        return files.mapNotNull { file ->
            val path = runCatching { childPath(directory, file.name) }.getOrNull() ?: return@mapNotNull null
            if (!allowed(path) || file.canonicalFile != file.absoluteFile ||
                (phoneBase == null && (file.name.endsWith(".new") || file.name.endsWith(".bak"))) ||
                (!file.isDirectory && !file.isFile)) return@mapNotNull null
            WorkspaceEntry(file.name, file.isDirectory, if (file.isDirectory) 0 else file.length())
        }.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    internal fun existingFile(path: String): File = resolve(path).also { check(it.isFile && it.canRead()) { "文件已移动或不可读取" } }

    private fun bytes(path: String): ByteArray {
        val file = if (phoneBase == null) resolve(path) else existingFile(path)
        require(file.length() <= MAX_BYTES) { "仅支持 1 MB 以内的文本，可使用其他应用打开" }
        val stream = if (phoneBase == null) AtomicFile(file).openRead() else file.inputStream()
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                require(output.size() + n <= MAX_BYTES) { "文件超过 1 MB" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }

    fun read(path: String): String = decode(bytes(path))

    fun readChunk(path: String, offset: Int): TextChunk {
        require(offset >= 0) { "起始位置无效" }
        val file = existingFile(path)
        val size = file.length()
        val modified = file.lastModified()
        require(size <= MAX_READ_BYTES) { "直接读取支持 50 MB 以内的文本" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = java.io.InputStreamReader(file.inputStream(), decoder).buffered().use { reader ->
            reader.mark(1)
            if (reader.read() != 0xfeff) reader.reset()
            var skipped = 0
            while (skipped < offset) {
                val count = reader.skip((offset - skipped).toLong())
                if (count == 0L) break
                skipped += count.toInt()
            }
            val buffer = CharArray(6000)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            val text = StringBuilder().append(buffer, 0, count)
            if (text.lastOrNull()?.isHighSurrogate() == true) {
                val next = reader.read()
                if (next >= 0) text.append(next.toChar())
            }
            require('\u0000' !in text) { "这个文件不是可读取的文本" }
            reader.mark(1)
            val more = reader.read() >= 0
            reader.reset()
            TextChunk(text.toString(), if (more) skipped + text.length else null, size)
        }
        check(file.length() == size && file.lastModified() == modified) { "文件在读取期间发生变化，请重新读取" }
        return result
    }


    fun save(name: String, text: String, create: Boolean = false, expected: String? = null) {
        validateRelativePath(name)
        val target = resolve(name)
        check(target.parentFile?.isDirectory == true) { "上级文件夹不存在" }
        var data = text.toByteArray(Charsets.UTF_8)
        if (phoneBase != null && !create) {
            val original = bytes(name)
            require(expected != null && decode(original) == expected) { "原文件已被修改，请导出草稿或重新打开" }
            if (original.size >= 3 && original[0] == 0xef.toByte() && original[1] == 0xbb.toByte() && original[2] == 0xbf.toByte())
                data = original.copyOfRange(0, 3) + data
        }
        require(data.size <= MAX_BYTES) { "仅支持 1 MB 以内的文本" }
        if (phoneBase != null) {
            if (create) {
                val stream = try {
                    java.nio.file.Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW)
                } catch (_: java.nio.file.FileAlreadyExistsException) { error("已有同名文件，请换个名字") }
                try { stream.use { it.write(data) } } catch (failure: Exception) { target.delete(); throw failure }
            } else {
                val temp = File.createTempFile(".vela-", ".tmp", target.parentFile)
                try {
                    temp.outputStream().use { it.write(data); it.fd.sync() }
                    check(read(name) == expected) { "保存期间原文件已变化" }
                    java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { temp.delete() }
            }
        } else {
            if (create) require(!target.exists() && !File(target.path + ".bak").exists()) { "已有同名文件，请换个名字" }
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try { stream.write(data); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
    }
    companion object {
        const val MAX_READ_BYTES = 50L * 1024 * 1024
        const val MAX_BYTES = 1024 * 1024
        fun decode(bytes: ByteArray): String {
            require(bytes.size <= MAX_BYTES) { "仅支持 1 MB 以内的文本" }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            require(!text.contains('\u0000')) { "这个文件不是可编辑的文本" }
            return text
        }
    }
}

object WorkspaceNames {
    fun validate(name: String) {
        require(name.isNotBlank() && name.length <= 100 && name == name.trim() && name !in listOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } && !name.endsWith(".bak") && !name.endsWith(".new")) {
            "文件名最多 100 字，不能包含路径或保留后缀"
        }
    }
}

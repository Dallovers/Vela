package com.dallovers.phoneagent

data class ReplyPart(val label: String, val text: String, val extension: String)
data class SavedReplyFile(val name: String, val sha256: String, val messageId: String)

object ReplyFiles {
    fun parts(reply: String): List<ReplyPart> {
        val result = mutableListOf(ReplyPart("完整回复", reply, "md"))
        var ticks = 0
        var language = ""
        val body = StringBuilder()
        reply.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            if (ticks == 0) {
                val match = Regex("^(`{3,})([^`]*)$").matchEntire(trimmed)
                if (match != null) { ticks = match.groupValues[1].length; language = match.groupValues[2].trim(); body.clear() }
            } else if (trimmed.trim().let { it.length >= ticks && it.all { c -> c == '`' } }) {
                val extension = when (language.lowercase()) {
                    "python", "py" -> "py"; "javascript", "js" -> "js"; "typescript", "ts" -> "ts"
                    "json" -> "json"; "html" -> "html"; "css" -> "css"; "kotlin", "kt" -> "kt"
                    "bash", "shell", "sh" -> "sh"; "markdown", "md" -> "md"; "csv" -> "csv"; else -> "txt"
                }
                if (result.size < 13) result += ReplyPart("代码块 ${result.size} · ${language.ifBlank { "文本" }}", body.toString(), extension)
                ticks = 0
            } else { body.append(line).append('\n') }
        }
        return result
    }
}

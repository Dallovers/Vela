package com.dallovers.phoneagent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/** Presentation only: original messages and exported code remain byte-for-byte intact. */
internal fun narrativeText(text: String): String = if ("\\n\\n" in text && "```" !in text && !Regex("[A-Za-z]:\\\\").containsMatchIn(text)) text.replace("\\n", "\n") else text

@Composable
internal fun MessageBody(text: String) {
    val context = LocalContext.current
    val code = remember(text) { Regex("(?ms)^```([^`\\n]*)\\n(.*?)^```[ \\t]*(?:\\n|$)").findAll(text).toList() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var position = 0
        fun plain(value: String) = buildAnnotatedString {
            val content = narrativeText(value.trim())
            val urls = Regex("https?://[^\\s<>\"，。；）)]+").findAll(content)
            var offset = 0
            urls.forEach { match ->
                append(content.substring(offset, match.range.first))
                withLink(LinkAnnotation.Url(match.value)) { append(match.value) }
                offset = match.range.last + 1
            }
            append(content.substring(offset))
        }
        code.forEach { match ->
            if (match.range.first > position) SelectionContainer { Text(plain(text.substring(position, match.range.first)), style = MaterialTheme.typography.bodyLarge) }
            val body = match.groupValues[2]
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(match.groupValues[1].ifBlank { "代码" }, Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("代码", body)) }) { Text("复制代码") }
                    }
                    SelectionContainer { Text(body.trimEnd(), Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)) }
                }
            }
            position = match.range.last + 1
        }
        if (position < text.length) SelectionContainer { Text(plain(text.substring(position)), style = MaterialTheme.typography.bodyLarge) }
    }
}

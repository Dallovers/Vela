package com.dallovers.phoneagent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionListItem(session: TaskSession, selected: Boolean, managing: Boolean, checked: Boolean,
                             onOpen: () -> Unit, onToggle: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(session.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (managing) Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = session.status != SessionStatus.RUNNING,
                modifier = Modifier.semantics { contentDescription = "选择对话" })
            Surface(onClick = if (managing) onToggle else onOpen, color = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(start = if (managing) 0.dp else 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (session.pinned) "置顶  " else "") + session.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(session.status.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!managing) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "对话菜单" }) { AgentGlyph("more") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(if (session.pinned) "取消置顶" else "置顶") }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(text = { Text(if (session.archived) "移出归档" else "归档") }, enabled = session.status != SessionStatus.RUNNING && session.question == null, onClick = { menu = false; onArchive() })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, enabled = session.status != SessionStatus.RUNNING, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

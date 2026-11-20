package com.dallovers.phoneagent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small native line glyphs. Accessible names live on their parent buttons. */
@Composable
internal fun AgentGlyph(name: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(22.dp)) {
        val unit = size.width / 24f
        fun line(x: Float, y: Float, ex: Float, ey: Float) = drawLine(color,
            Offset(x * unit, y * unit), Offset(ex * unit, ey * unit), 1.7f * unit, StrokeCap.Round)
        when (name) {
            "mic" -> { drawRoundRect(color, Offset(9f*unit,2f*unit), Size(6f*unit,12f*unit), CornerRadius(3f*unit), style=Stroke(1.7f*unit))
                drawArc(color, 0f, 180f, false, Offset(5f*unit,5f*unit), Size(14f*unit,13f*unit), style=Stroke(1.7f*unit)); line(12f,18f,12f,22f) }
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "image", "camera", "file" -> {
                drawRoundRect(color, Offset(3f*unit,5f*unit), Size(18f*unit,15f*unit), CornerRadius(2f*unit), style=Stroke(1.7f*unit))
                when(name) {
                    "camera" -> { drawCircle(color, 4f*unit, Offset(12f*unit,12f*unit), style=Stroke(1.7f*unit)); line(9f,3f,15f,3f) }
                    "image" -> { line(4f,18f,10f,12f); line(10f,12f,14f,16f); line(14f,16f,17f,13f); drawCircle(color,1.5f*unit,Offset(16f*unit,9f*unit)) }
                    else -> { line(7f,10f,17f,10f); line(7f,14f,14f,14f) }
                }
            }
            "more" -> { for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.7f * unit, Offset(x * unit, 12f * unit)) }
            "menu" -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 16f, 18f) }
            "plus" -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            "send" -> { line(12f, 20f, 12f, 4f); line(5f, 11f, 12f, 4f); line(19f, 11f, 12f, 4f) }
            "back" -> { line(4f, 12f, 20f, 12f); line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f) }
            "new" -> { line(14f, 5f, 19f, 10f); line(8f, 16f, 8f, 12f); line(8f, 12f, 17f, 3f); line(17f, 3f, 21f, 7f); line(21f, 7f, 12f, 16f); line(12f, 16f, 8f, 16f); line(4f, 5f, 4f, 21f); line(4f, 21f, 20f, 21f); line(20f, 21f, 20f, 14f) }
            "stop" -> drawRoundRect(color, Offset(6f*unit,6f*unit), Size(12f*unit,12f*unit), CornerRadius(2f*unit))
            "phone" -> { drawRoundRect(color, Offset(6f*unit,2f*unit), Size(12f*unit,20f*unit), CornerRadius(3f*unit), style=Stroke(1.7f*unit)); line(10f,18f,14f,18f) }
            "check" -> { line(5f,12f,10f,17f); line(10f,17f,20f,6f) }
            else -> { drawCircle(color, 7f*unit, style=Stroke(1.7f*unit)); drawCircle(color, 2f*unit) }
        }
    }
}

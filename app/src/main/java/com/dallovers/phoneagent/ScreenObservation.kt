package com.dallovers.phoneagent

import kotlin.math.abs

data class TargetBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX get() = (left + right) / 2.0
    val centerY get() = (top + bottom) / 2.0
    val width get() = right - left
    val height get() = bottom - top
    fun closeTo(other: TargetBounds): Boolean = abs(centerX - other.centerX) <= 18 &&
        abs(centerY - other.centerY) <= 18 && abs(width - other.width) <= 24 && abs(height - other.height) <= 24
}

data class ScreenTarget(
    val id: String,
    val label: String,
    val source: String,
    val role: String,
    val bounds: TargetBounds,
    val state: String = "",
    val viewId: String = "",
    val windowId: Int = -1,
) {
    fun matches(other: ScreenTarget): Boolean = label == other.label && source == other.source &&
        role == other.role && state == other.state && viewId == other.viewId && windowId == other.windowId && bounds.closeTo(other.bounds)
}

data class ScreenObservation(
    val id: String,
    val packageName: String,
    val width: Int,
    val height: Int,
    val keyboardVisible: Boolean,
    val targets: List<ScreenTarget>,
    val pixels: List<Int> = emptyList(),
) {
    fun findUnchangedTarget(target: ScreenTarget, fresh: ScreenObservation): ScreenTarget? {
        if (packageName != fresh.packageName || width != fresh.width || height != fresh.height ||
            keyboardVisible != fresh.keyboardVisible) return null
        return fresh.targets.filter(target::matches).singleOrNull()
    }

    fun sameLayoutAs(other: ScreenObservation): Boolean = packageName == other.packageName &&
        keyboardVisible == other.keyboardVisible && targets.isNotEmpty() && targets.size == other.targets.size &&
        targets.all { target -> other.targets.any(target::matches) }

    // The no-progress check ignores OCR box jitter and status-bar clocks. Native checked state is retained.
    fun contentSignature(): List<String> = targets.map { "${it.source}|${it.role}|${it.label}|${it.state}" }.sorted()

    fun visiblySameAs(other: ScreenObservation): Boolean {
        if (!sameLayoutAs(other)) return false
        if (pixels.isEmpty() || other.pixels.isEmpty()) return true
        if (pixels.size != other.pixels.size) return false
        return pixels.zip(other.pixels).sumOf { (a, b) ->
            abs((a shr 16 and 255) - (b shr 16 and 255)) + abs((a shr 8 and 255) - (b shr 8 and 255)) + abs((a and 255) - (b and 255))
        }.toDouble() / (pixels.size * 3) < 6.0
    }
}

package com.dallovers.phoneagent

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenReader {
    private val recognizerDelegate = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val recognizer by recognizerDelegate
    var diagnostic = "尚未识别屏幕"
        private set

    fun close() {
        if (recognizerDelegate.isInitialized()) recognizer.close()
    }

    suspend fun observe(bitmap: Bitmap, root: AccessibilityNodeInfo?, area: Rect, packageName: String,
        keyboardVisible: Boolean, id: String): ScreenObservation {
        val native = nativeTargets(root, area)
        val ocr = try {
            val result = suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val bounds = line.boundingBox?.let(::Rect) ?: return@mapNotNull null
                val label = line.text.trim().take(240)
                // OCR may read a plus, arrow, or magnifier as a one-character word.
                // Keep real text regions; unlabeled icons use the screenshot coordinate grid.
                if (label.none { it.isLetterOrDigit() } || !bounds.intersect(area)) return@mapNotNull null
                if (native.any { it.label == label && it.bounds.let { b ->
                        bounds.centerX() in b.left..b.right && bounds.centerY() in b.top..b.bottom
                    } }) return@mapNotNull null
                ScreenTarget("", label, "ocr", "text_region", bounds.targetBounds())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val nativeIndexed = native.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(100).mapIndexed { index, target -> target.copy(id = "N${index + 1}") }
        val ocrOrdered = ocr.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        // Reserve lower-screen labels such as a composer button even on text-heavy pages.
        val boundedOcr = if (ocrOrdered.size <= 120) ocrOrdered else ocrOrdered.take(80) + ocrOrdered.takeLast(40)
        val ocrIndexed = boundedOcr.mapIndexed { index, target -> target.copy(id = "V${index + 1}") }
        diagnostic = "screen=$id package=$packageName native=${nativeIndexed.size} ocr=${ocrIndexed.size} keyboard=$keyboardVisible"
        val pixels = (1..24).flatMap { row -> (1..12).map { column ->
            bitmap.getPixel(area.left + column * (area.width() - 1) / 13, area.top + row * (area.height() - 1) / 25)
        } }
        return ScreenObservation(id, packageName, bitmap.width, bitmap.height, keyboardVisible, nativeIndexed + ocrIndexed, pixels)
    }

    internal fun nativeTargets(root: AccessibilityNodeInfo?, area: Rect): List<ScreenTarget> {
        if (root == null) return emptyList()
        val targets = mutableListOf<ScreenTarget>()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 2500) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && !node.isPassword && (node.isClickable || node.isEditable)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.intersect(area) && bounds.width().toLong() * bounds.height() < area.width().toLong() * area.height() / 2) {
                    val ownLabel = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }
                    val label = (ownLabel ?: childLabel(node) ?: if (node.isEditable) "输入框" else "").trim().take(240)
                    if (label.isNotEmpty()) targets += ScreenTarget("", label, "native",
                        if (node.isEditable) "input" else "control", bounds.targetBounds(),
                        if (node.isCheckable) "checked=${node.isChecked}" else "",
                        node.viewIdResourceName.orEmpty(), node.windowId)
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return targets.distinctBy { listOf(it.label, it.bounds, it.role) }
    }

    internal fun nativeNode(root: AccessibilityNodeInfo?, target: ScreenTarget): AccessibilityNodeInfo? {
        if (root == null) return null
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 2500) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.isEnabled && !node.isPassword && (node.isClickable || node.isEditable)) {
                val box = Rect().also(node::getBoundsInScreen).targetBounds()
                val label = (node.text?.toString()?.takeIf(String::isNotBlank)
                    ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                    ?: node.hintText?.toString()?.takeIf(String::isNotBlank)
                    ?: childLabel(node) ?: if (node.isEditable) "输入框" else "").trim().take(240)
                val state = if (node.isCheckable) "checked=${node.isChecked}" else ""
                if (label == target.label && state == target.state && box.closeTo(target.bounds) && node.windowId == target.windowId &&
                    node.viewIdResourceName.orEmpty() == target.viewId && (node.isEditable == (target.role == "input"))) matches += node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::add)
        }
        return matches.singleOrNull()
    }

    private fun childLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth >= 3 || node.isPassword) return null
        val parts = mutableListOf<String>()
        for (index in 0 until minOf(node.childCount, 8)) {
            val child = node.getChild(index) ?: continue
            if (!child.isVisibleToUser || child.isPassword) continue
            (child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: childLabel(child, depth + 1))?.let(parts::add)
        }
        return parts.distinct().joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun Rect.targetBounds() = TargetBounds(left, top, right, bottom)
}

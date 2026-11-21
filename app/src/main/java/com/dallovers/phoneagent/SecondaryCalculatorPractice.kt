package com.dallovers.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withTimeout

/** The USB prototype targets one already provisioned scrcpy display and one known app. */
internal class SecondaryCalculatorPractice(
    private val service: AccessibilityService,
    private val reader: ScreenReader,
    private val readBitmap: suspend (Int) -> Bitmap,
) {
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)

    suspend fun run(calculation: SecondaryCalculation, onStep: (Int, Int) -> Unit): Int {
        val display = displays.displays.singleOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.name == "scrcpy" && it.isValid }
            ?: error("请先通过 USB 打开一个 scrcpy 副屏，并在其中打开计算器。")
        val window = calculatorRoot(display).windowId
        val keys = listOf(setOf("AC", "清除", "清空", "全部清除", "清除全部", "清除所有")) +
            calculation.expression.map { if (it == '+') setOf("+", "＋", "加", "加号") else setOf(it.toString()) } +
            listOf(setOf("=", "＝", "等于", "等号"))
        for ((index, labels) in keys.withIndex()) {
            currentCoroutineContext().ensureActive()
            calculatorRoot(display, window)
            val rotation = display.rotation
            val bitmap = calculatorBitmap(display, window)
            val target = try {
                val root = calculatorRoot(display, window)
                val area = Rect(0, 0, bitmap.width, bitmap.height)
                fun matching(targets: List<ScreenTarget>) = targets.filter {
                    it.label.trim().uppercase() in labels && it.bounds.centerY > bitmap.height / 2.0
                }
                val native = matching(reader.nativeTargets(root, area))
                val candidates = native.ifEmpty {
                    matching(reader.observe(bitmap, root, area, CALCULATOR, false, "vd-${index + 1}").targets)
                }
                candidates.singleOrNull()
                    ?: error("副屏未找到唯一的计算器按键「${labels.first()}」，试运行已暂停。")
            } finally { bitmap.recycle() }
            calculatorRoot(display, window)
            check(display.rotation == rotation) { "副屏方向发生变化，试运行已暂停。" }
            tap(display.displayId, target.bounds.centerX.toFloat(), target.bounds.centerY.toFloat())
            onStep(index + 1, keys.size)
            delay(420)
        }
        repeat(3) { attempt ->
            val result = calculatorBitmap(display, window)
            try {
                val root = calculatorRoot(display, window)
                // Exclude the calculator's toolbar and keypad, which confuse recognition of short numbers.
                val resultArea = Rect(0, result.height / 4, result.width, result.height / 2)
                val nativeLabels = resultLabels(root, resultArea)
                if (calculation.matchesReadback(nativeLabels) ||
                    calculation.matchesReadback(nativeLabels + recognizeResult(result, resultArea, attempt))) {
                    calculatorRoot(display, window)
                    return display.displayId
                }
            } finally { result.recycle() }
            delay(400)
        }
        error("没有从副屏核对到完整的算式和结果。请检查计算器。")
    }

    private suspend fun calculatorBitmap(display: Display, window: Int): Bitmap {
        calculatorRoot(display, window)
        return try {
            readBitmap(display.displayId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            calculatorRoot(display, window)
            throw failure
        }
    }

    private suspend fun recognizeResult(bitmap: Bitmap, area: Rect, attempt: Int): List<String> {
        val crop = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint()
            if (Color.luminance(bitmap.getPixel(0, area.top)) < 0.5f) {
                // OCR works better with dark glyphs on a light background.
                paint.colorFilter = ColorMatrixColorFilter(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
            val localArea = Rect(0, 0, crop.width, crop.height)
            Canvas(crop).drawBitmap(bitmap, area, localArea, paint)
            return reader.observe(crop, null, localArea, CALCULATOR, false, "vd-result-$attempt").targets.map { it.label }
        } finally {
            crop.recycle()
        }
    }

    private fun resultLabels(root: AccessibilityNodeInfo, area: Rect): List<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val labels = mutableListOf<String>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 800) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && area.contains(bounds.centerX(), bounds.centerY())) {
                node.text?.toString()?.let(labels::add)
                node.contentDescription?.toString()?.let(labels::add)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return labels
    }

    private fun calculatorRoot(display: Display, expectedWindow: Int? = null): AccessibilityNodeInfo {
        check(!keyguard.isKeyguardLocked) { "手机已锁屏，副屏试运行已暂停。" }
        check(display.isValid && displays.getDisplay(display.displayId)?.name == "scrcpy") { "副屏已断开，试运行已暂停。" }
        val top = service.windowsOnAllDisplays.get(display.displayId).orEmpty().firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }?.root
        check(top != null && top.packageName?.toString() == CALCULATOR &&
            (expectedWindow == null || top.windowId == expectedWindow)) { "副屏计算器窗口已切换，试运行已暂停。" }
        return top
    }

    private suspend fun tap(displayId: Int, x: Float, y: Float) {
        check(displayId != Display.DEFAULT_DISPLAY)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        try {
            withTimeout(3000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    if (!service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("副屏手势被系统中断。"))
                        }
                    }, null) && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("系统未接受副屏手势。"))
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            error("副屏手势未收到系统回执，试运行已停止。")
        }
    }

    private companion object {
        const val CALCULATOR = "com.coloros.calculator"
    }
}

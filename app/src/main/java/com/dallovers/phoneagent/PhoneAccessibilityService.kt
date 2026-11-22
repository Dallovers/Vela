package com.dallovers.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScreenFrame(val bitmap: Bitmap, val rotation: Int, val observation: ScreenObservation? = null, val editorIdentity: String? = null)

// Only raised before an action has been dispatched. The agent can safely choose another action.
open class InputActionUnavailableException(message: String) : IllegalStateException(message)
class TextInputUnavailableException(message: String) : InputActionUnavailableException(message)

class PhoneAccessibilityService : AccessibilityService() {
    private val controller get() = (application as AgentApplication).controller
    private var stopButton: View? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val questionOverlay by lazy { QuestionOverlay(this, controller) }
    private var receiverRegistered = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) questionOverlay.conceal()
            else questionOverlay.screenAvailable()
        }
    }
    private var lastInputDiagnostic = "尚未执行文字输入"
    private val screenReader = ScreenReader()
    private var screenSequence = 0L
    private var lastObservation: ScreenObservation? = null
    private var lastTargetDiagnostic = "尚未执行目标点击"
    private val windowManager get() = getSystemService(WindowManager::class.java)

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= 33) {
            serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
        }
        controller.attach(this)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(lockReceiver, filter)
            receiverRegistered = true
            overlayScope.launch { controller.state.collect { questionOverlay.render(it) } }
        }
        questionOverlay.screenAvailable()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { questionOverlay.render(controller.state.value) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        questionOverlay.render(controller.state.value, rebuild = true)
    }
    fun hideQuestionControl() { questionOverlay.clear() }
    fun refreshQuestionControl() { questionOverlay.render(controller.state.value, rebuild = true) }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        controller.dump(writer)
        writer.println("questionOverlayVisible=${questionOverlay.visible}")
        writer.println("input=$lastInputDiagnostic")
        writer.println("observation=${screenReader.diagnostic}")
        writer.println("target=$lastTargetDiagnostic")
        val findIndex = args?.indexOf("--find-target") ?: -1
        if (findIndex >= 0) {
            val label = args?.getOrNull(findIndex + 1)
            lastObservation?.targets?.filter { it.label == label }?.forEach {
                writer.println("matchedTarget=${it.id} source=${it.source} bounds=${it.bounds}")
            }
        }
        if (args?.contains("--keypad") == true) {
            val keys = applicationRoot()?.let(::numericKeypad)
            writer.println("keypad=${keys?.mapValues { it.value.toShortString() }}")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val editor = inputMethod?.currentInputEditorInfo
            writer.println("inputConnection=${inputMethod?.currentInputConnection != null} inputStarted=${inputMethod?.currentInputStarted} editorPackage=${editor?.packageName} inputType=${editor?.inputType}")
        }
    }
    override fun onInterrupt() { controller.interruptPhoneOperation("操作服务被系统中断，请核对当前页面。") }

    override fun onUnbind(intent: Intent?): Boolean {
        questionOverlay.clear()
        controller.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        questionOverlay.clear()
        hideStopControl()
        if (receiverRegistered) { unregisterReceiver(lockReceiver); receiverRegistered = false }
        overlayScope.cancel()
        controller.detach(this)
        screenReader.close()
        super.onDestroy()
    }

    fun rotation(): Int = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).rotation

    suspend fun capture(): ScreenFrame {
        check(!questionOverlay.visible) { "请先回答浮层中的问题，再继续读取屏幕" }
        check(!getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "手机已锁屏，请解锁后重新开始" }
        stopButton?.visibility = View.INVISIBLE
        try {
            delay(120)
            val rotationBefore = rotation()
            val bitmap = suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            if (continuation.isActive) {
                                val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?: error("截图解码失败，请重试")
                                val copy = try { wrapped.copy(Bitmap.Config.ARGB_8888, false) } finally { wrapped.recycle() }
                                checkNotNull(copy) { "截图内存不足，请结束其他应用后重试" }
                                continuation.resume(copy)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally { buffer.close() }
                    }

                    // API 34 adds the secure-window code, which the callback's IntDef omits.
                    @SuppressLint("SwitchIntDef")
                    override fun onFailure(errorCode: Int) {
                        val message = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图间隔过短，请稍后重试"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "请重新启用无障碍操作服务"
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面受到系统保护，请切换到普通界面后重试"
                            else -> "截图失败，错误码 $errorCode，请重新启用操作服务后重试"
                        }
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                    }
                })
            }
            if (rotationBefore != rotation()) {
                bitmap.recycle()
                error("截图期间屏幕方向发生变化，请重新开始")
            }
            val root = applicationRoot()
            val packageName = root?.packageName?.toString().orEmpty()
            val screenId = "s${SystemClock.elapsedRealtime().toString(36)}-${++screenSequence}"
            // The image has already been captured; keep the stop control available during OCR.
            stopButton?.visibility = View.VISIBLE
            val observation = screenReader.observe(bitmap, root, contentArea(), packageName,
                windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }, screenId)
            lastObservation = observation
            return ScreenFrame(bitmap, rotationBefore, observation, editorIdentity())
        } finally { stopButton?.visibility = View.VISIBLE }
    }

    suspend fun execute(action: AgentAction, frame: ScreenFrame? = null) {
        check(controller.state.value.sessions.none { it.status == SessionStatus.NEEDS_USER && it.question != null }) { "正在等待你的答复，手机操作已暂停" }
        check(!questionOverlay.visible) { "待答浮层尚未收起，手机操作未执行" }

        currentCoroutineContext().ensureActive()
        if (frame != null) check(frame.rotation == rotation()) { "屏幕方向已变化，请重新开始任务" }
        frame?.observation?.let { observed ->
            if (action.kind !in setOf("home", "back", "open_settings", "wait", "finish") &&
                applicationRoot()?.packageName?.toString() != observed.packageName) {
                throw InputActionUnavailableException("前台应用已变化，动作尚未执行，请重新观察屏幕")
            }
        }
        when (action.kind) {
            "tap", "long_press", "swipe" -> {
                if (frame != null) verifyCoordinateFrame(frame, action)
                val bounds = windowManager.maximumWindowMetrics.bounds
                val width = frame?.bitmap?.width ?: bounds.width()
                val height = frame?.bitmap?.height ?: bounds.height()
                val path = Path().apply {
                    moveTo((action.x * (width - 1)).toFloat(), (action.y * (height - 1)).toFloat())
                    if (action.kind == "swipe") lineTo((action.endX * (width - 1)).toFloat(), (action.endY * (height - 1)).toFloat())
                }
                val duration = when (action.kind) {
                    "tap" -> 80L
                    "long_press" -> maxOf(action.duration, ViewConfiguration.getLongPressTimeout().toLong() + 100)
                    else -> action.duration
                }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
                stopButton?.visibility = View.INVISIBLE
                try {
                    delay(80)
                    currentCoroutineContext().ensureActive()
                    if (frame != null && frame.rotation != rotation())
                        throw InputActionUnavailableException("手势提交前屏幕方向已变化，未执行")
                    if (frame?.observation != null && applicationRoot()?.packageName?.toString() != frame.observation.packageName)
                        throw InputActionUnavailableException("手势提交前应用已变化，未执行")
                    suspendCancellableCoroutine<Unit> { continuation ->
                        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("手势被中断，请重新开始"))
                            }
                        }, null)
                        if (!accepted && continuation.isActive) continuation.resumeWithException(IllegalStateException("系统暂时无法执行手势，请重试"))
                    }
                } finally { stopButton?.visibility = View.VISIBLE }
            }
            "back" -> check(performGlobalAction(GLOBAL_ACTION_BACK)) { "系统未接受返回动作" }
            "home" -> check(performGlobalAction(GLOBAL_ACTION_HOME)) { "系统未接受回桌面动作" }
            "open_settings" -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "open_app" -> openApp(action.text)
            "type_text" -> {
                if (frame != null && frame.editorIdentity != editorIdentity()) throw TextInputUnavailableException("观察之后输入焦点已变化，未填写文字，请重新点击输入框")
                typeText(action.text)
            }
            "tap_element" -> tapElement(action, frame)
            "scroll" -> scroll(action, frame)
            "tap_key" -> {
                val bounds = applicationRoot()?.let(::numericKeypad)?.get(action.text)
                    ?: throw InputActionUnavailableException("没有找到可唯一定位的数字键 ${action.text}，请根据截图使用 tap 点击对应按键")
                val screen = windowManager.maximumWindowMetrics.bounds
                controller.log("按键定位 · ${action.text} · ${bounds.toShortString()}")
                execute(AgentAction("tap", action.reason,
                    x = bounds.exactCenterX().toDouble() / (screen.width() - 1),
                    y = bounds.exactCenterY().toDouble() / (screen.height() - 1)), frame)
            }
            "wait" -> delay(action.duration)
            "finish" -> Unit
        }
    }

    private fun contentArea(): Rect {
        val metrics = windowManager.maximumWindowMetrics
        val screen = metrics.bounds
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val area = Rect(screen.left + insets.left, screen.top + insets.top,
            screen.right - insets.right, screen.bottom - insets.bottom)
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.let {
            val app = Rect().also(it::getBoundsInScreen)
            if (!app.isEmpty) check(area.intersect(app)) { "当前应用不在可操作区域" }
        }
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
            val ime = Rect().also(it::getBoundsInScreen)
            if (ime.top > area.top && ime.top < area.bottom) area.bottom = ime.top
        }
        return area
    }

    private suspend fun tapElement(action: AgentAction, frame: ScreenFrame?) {
        val expected = frame?.observation ?: throw InputActionUnavailableException("缺少目标所属的屏幕观察")
        if (action.screenId != expected.id) throw InputActionUnavailableException("目标来自旧屏幕，已拒绝点击，请重新观察")
        val target = expected.targets.singleOrNull { it.id == action.targetId }
            ?: throw InputActionUnavailableException("本次屏幕没有这个目标编号，请使用当前列表中的编号")
        if (target.source == "native" && target.role == "control") {
            val root = applicationRoot()
            val freshKeyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val current = screenReader.nativeTargets(root, contentArea()).singleOrNull(target::matches)
            if (root?.packageName?.toString() != expected.packageName || freshKeyboard != expected.keyboardVisible || current == null)
                throw InputActionUnavailableException("原生控件、前台窗口或键盘已变化，未点击")
            val node = screenReader.nativeNode(root, current)
                ?: throw InputActionUnavailableException("原生控件已失效或不唯一，未点击")
            currentCoroutineContext().ensureActive()
            if (node.isClickable) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    lastTargetDiagnostic = "screen=${expected.id} id=${target.id} source=native rechecked=true"
                    controller.log("目标点击已复核 · ${target.id} · 原生控件方式")
                    return
                }
                // performAction 返回 false 表示系统没有执行该动作。部分控件（如设置页搜索框）
                // 标记了 clickable 却拒绝 ACTION_CLICK，此时降级为坐标点击，避免整条路径不可用。
                // 若刚才那下其实已生效，重新观察时会因目标或画面已变化被拒绝，不会重复点击。
                controller.log("原生点击未确认 · ${target.id} · 已降级为坐标点击")
            }
        }
        val fresh = capture()
        try {
            val current = fresh.observation?.let { expected.findUnchangedTarget(target, it) }
                ?: throw InputActionUnavailableException("目标文字、位置或输入法状态已变化，已拒绝旧位置点击，请重新观察")
            lastTargetDiagnostic = "screen=${expected.id} id=${target.id} source=${current.source} bounds=${current.bounds} rechecked=true"
            controller.log("目标点击已复核 · ${target.id} · ${current.source} · (${current.bounds.centerX.toInt()},${current.bounds.centerY.toInt()})")
            currentCoroutineContext().ensureActive()
            if (applicationRoot()?.packageName?.toString() != expected.packageName)
                throw InputActionUnavailableException("点击前应用已变化，未执行")
            if (current.source == "native") {
                val node = screenReader.nativeNode(applicationRoot(), current)
                    ?: throw InputActionUnavailableException("原生控件已失效或不唯一，未点击")
                if (node.isClickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        controller.log("目标点击已提交 · 原生控件方式")
                        return
                    }
                    controller.log("原生点击未确认 · ${target.id} · 已降级为坐标点击")
                }
            }
            execute(AgentAction("tap", action.reason,
                x = current.bounds.centerX / (fresh.bitmap.width - 1),
                y = current.bounds.centerY / (fresh.bitmap.height - 1)), fresh)
        } finally { fresh.bitmap.recycle() }
    }

    private suspend fun verifyCoordinateFrame(frame: ScreenFrame, action: AgentAction) {
        val expected = frame.observation ?: return
        val fresh = capture()
        try {
            val current = fresh.observation
            if (current == null || expected.packageName != current.packageName ||
                expected.keyboardVisible != current.keyboardVisible || !similarPixels(frame.bitmap, fresh.bitmap) ||
                (action.kind != "swipe" && !sameTapRegion(frame.bitmap, fresh.bitmap, action.x, action.y))) {
                throw InputActionUnavailableException("画面或键盘布局已变化，旧坐标未执行，请重新观察并优先选择目标编号")
            }
        } finally { fresh.bitmap.recycle() }
    }

    private fun sameTapRegion(before: Bitmap, after: Bitmap, normalizedX: Double, normalizedY: Double): Boolean {
        if (before.width != after.width || before.height != after.height) return false
        val x = (normalizedX * (before.width - 1)).toInt()
        val y = (normalizedY * (before.height - 1)).toInt()
        var difference = 0L
        var count = 0
        for (dx in -30..30 step 6) for (dy in -30..30 step 6) {
            val px = (x + dx).coerceIn(0, before.width - 1)
            val py = (y + dy).coerceIn(0, before.height - 1)
            val a = before.getPixel(px, py); val b = after.getPixel(px, py)
            difference += kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
            count += 3
        }
        return difference.toDouble() / count < 12.0
    }

    private fun similarPixels(before: Bitmap, after: Bitmap): Boolean {
        if (before.width != after.width || before.height != after.height) return false
        var difference = 0L
        var count = 0
        for (row in 5..45) for (column in 1..23) {
            val x = column * (before.width - 1) / 24
            val y = row * (before.height - 1) / 48
            val a = before.getPixel(x, y)
            val b = after.getPixel(x, y)
            difference += kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))
            count += 3
        }
        return difference.toDouble() / count < 5.0
    }

    private suspend fun scroll(action: AgentAction, frame: ScreenFrame?) {
        val screen = windowManager.maximumWindowMetrics.bounds
        val insets = windowManager.maximumWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val area = Rect(screen.left + insets.left, screen.top + insets.top,
            screen.right - insets.right, screen.bottom - insets.bottom)
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.let {
            val appBounds = Rect().also(it::getBoundsInScreen)
            if (!appBounds.isEmpty) check(area.intersect(appBounds)) { "当前应用不在可滑动区域，请回到目标应用后重试" }
        }
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let {
            val keyboard = Rect().also(it::getBoundsInScreen)
            if (keyboard.top > area.top && keyboard.top < area.bottom) area.bottom = keyboard.top
        }
        check(area.width() > 100 && area.height() > 200) { "当前可滑动区域过小，请先关闭键盘或弹窗" }
        val centerX = area.exactCenterX().toDouble()
        val centerY = area.exactCenterY().toDouble()
        val lowX = area.left + area.width() * .2
        val highX = area.left + area.width() * .8
        val lowY = area.top + area.height() * .28
        val highY = area.top + area.height() * .78
        val points = when (action.direction) {
            "down" -> doubleArrayOf(centerX, highY, centerX, lowY)
            "up" -> doubleArrayOf(centerX, lowY, centerX, highY)
            "right" -> doubleArrayOf(highX, centerY, lowX, centerY)
            "left" -> doubleArrayOf(lowX, centerY, highX, centerY)
            else -> error("翻页方向无效")
        }
        execute(AgentAction("swipe", action.reason,
            points[0] / (screen.width() - 1), points[1] / (screen.height() - 1),
            points[2] / (screen.width() - 1), points[3] / (screen.height() - 1), duration = 500), frame)
        controller.log("翻页手势已完成 · ${action.direction}")
    }

    private fun applicationRoot(): AccessibilityNodeInfo? = windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
    }?.root ?: windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
    }?.root ?: rootInActiveWindow

    private suspend fun typeText(text: String) {
        val root = applicationRoot()
        val input = root?.let(::editableInput)
        lastInputDiagnostic = "package=${root?.packageName} children=${root?.childCount} editable=${input != null}"
        if (input != null) {
            if (!input.isFocused) throw TextInputUnavailableException("输入框尚未获得焦点，先点击实际输入框再填写")
            val originalPackage = root.packageName?.toString()
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (input.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) error("系统未确认控件输入，可能已产生效果；已暂停，请核对，不要自动换方式重填")
                lastInputDiagnostic += " method=ACTION_SET_TEXT"
                repeat(5) {
                    delay(100)
                    if (applicationRoot()?.packageName?.toString() != originalPackage) error("文字已提交，但前台应用发生变化；请核对，不要自动重填")
                    if (input.refresh() && input.isFocused && input.text?.toString() == text) {
                        lastInputDiagnostic += " verified=true"
                        controller.log("文字填写已验证 · 控件方式")
                        return
                    }
                }
                error("文字已提交但未能读回确认；已暂停，请核对输入框，不要重复输入")
            }
        }
        if (Build.VERSION.SDK_INT < 33) {
            throw TextInputUnavailableException("当前页面没有可编辑控件；可点击屏幕上的按键输入，输入连接方式需要 Android 13 或更新版本")
        }
        // An app can expose a working editor without exposing an accessibility node.
        // Use Android's accessibility input connection, without replacing the keyboard or clipboard.
        val method = inputMethod
        val editor = method?.currentInputEditorInfo
        val connection = method?.currentInputConnection
        if (method?.currentInputStarted != true || editor == null || connection == null || editor.inputType == InputType.TYPE_NULL) {
            throw TextInputUnavailableException("当前页面没有文字输入连接；若有拨号盘或数字键盘，请点击对应按键；普通输入框需先点击编辑区域")
        }
        val targetPackage = root?.packageName?.toString()
        if (targetPackage == null || editor.packageName != targetPackage) {
            throw TextInputUnavailableException("输入焦点与前台应用不一致，请重新点击目标输入区域")
        }
        lastInputDiagnostic += " method=input_connection editorPackage=${editor.packageName}"
        val before = connection.getSurroundingText(8192, 8192, 0)
            ?: throw TextInputUnavailableException("输入框没有返回文字状态，请重新点击输入区域，或使用画面中的按键")
        if (before.text.isNotEmpty()) {
            connection.performContextMenuAction(android.R.id.selectAll)
            delay(100)
            val selected = connection.getSurroundingText(8192, 8192, 0)
            if (selected == null || selected.offset > 0 || selected.text.length >= 8192 || selected.selectionStart != 0 || selected.selectionEnd != selected.text.length) {
                throw TextInputUnavailableException("输入框未接受全选，尚未填写文字；可用画面中的清除或删除按键处理后再输入")
            }
        }
        if (applicationRoot()?.packageName?.toString() != targetPackage || method.currentInputEditorInfo?.fieldId != editor.fieldId) {
            throw TextInputUnavailableException("输入期间焦点已变化，尚未填写文字，请根据当前画面重新选择输入区域")
        }
        connection.commitText(text, 1, null)
        // commitText has no success return value. Check the editor before reporting success.
        repeat(5) {
            delay(100)
            if (applicationRoot()?.packageName?.toString() != targetPackage || method.currentInputEditorInfo?.fieldId != editor.fieldId)
                error("文字已提交，但输入焦点发生变化；请核对，不要自动重填")
            val after = connection.getSurroundingText(8192, 8192, 0)
            lastInputDiagnostic = lastInputDiagnostic.substringBefore(" readBackOffset=") +
                " readBackOffset=${after?.offset} readBackLength=${after?.text?.length}"
            // Legacy InputConnection implementations return -1 for an unknown absolute offset.
            if (after != null && after.offset <= 0 && after.text.toString() == text) {
                lastInputDiagnostic += " verified=true"
                controller.log("文字填写已验证 · 输入连接方式")
                return
            }
        }
        error("文字已提交但未能确认写入，请核对当前输入框后重试")
    }

    private fun editorIdentity(): String? {
        if (Build.VERSION.SDK_INT < 33 || inputMethod?.currentInputStarted != true) return null
        val editor = inputMethod?.currentInputEditorInfo ?: return null
        return "${editor.packageName}|${editor.fieldId}|${editor.inputType}|${editor.imeOptions}"
    }

    fun isLauncher(packageName: String): Boolean = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName == packageName

    fun launchableApps(): List<Pair<String, String>> = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }.distinct().sortedBy { it.first }

    private fun openApp(packageName: String) {
        if (launchableApps().none { it.second == packageName }) throw InputActionUnavailableException("该应用不在可启动列表，未打开")
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw InputActionUnavailableException("应用没有可用的启动入口")
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        controller.log("已提交打开应用，下一帧核对前台")
    }

    private fun editableInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        val editable = mutableListOf<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isEditable && node.isVisibleToUser && node.isEnabled) editable.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
        }
        return editable.singleOrNull()
    }

    private data class KeyTarget(val bounds: Rect, val priority: Int)

    // Find a complete keypad subtree so an entered number such as "2" is not mistaken for the 2 key.
    // This uses visible labels and geometry, without application IDs or device-specific coordinates.
    private fun numericKeypad(root: AccessibilityNodeInfo): Map<String, Rect>? {
        var bestDepth = -1
        var best: Map<String, Rect>? = null
        var visited = 0
        val keyLabel = Regex("^([0-9*#])(?:[\\s,，:：]+[a-zA-Z+\\s]+)?$")
        fun scan(node: AccessibilityNodeInfo, depth: Int): MutableMap<String, MutableList<KeyTarget>> {
            val targets = mutableMapOf<String, MutableList<KeyTarget>>()
            if (++visited > 4000 || depth > 60) return targets
            if (node.isVisibleToUser && node.isEnabled) {
                listOf(node.text, node.contentDescription).forEachIndexed { index, label ->
                    val value = label?.toString()?.trim().orEmpty()
                    keyLabel.matchEntire(value)?.let { match ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (!bounds.isEmpty) {
                            val priority = if (value.length == 1) index else index + 2
                            targets.getOrPut(match.groupValues[1]) { mutableListOf() }.add(KeyTarget(bounds, priority))
                        }
                    }
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    scan(child, depth + 1).forEach { (key, found) ->
                        targets.getOrPut(key) { mutableListOf() }.addAll(found)
                    }
                }
            }
            if (depth > bestDepth && ('0'..'9').all { it.toString() in targets }) {
                val unique = targets.mapNotNull { (key, found) ->
                    val priority = found.minOf { it.priority }
                    found.filter { it.priority == priority }.map { it.bounds }.distinct().singleOrNull()?.let { key to it }
                }.toMap()
                if (('0'..'9').all { it.toString() in unique } && unique.values.map { it.centerX() to it.centerY() }.distinct().size == unique.size) {
                    bestDepth = depth
                    best = unique
                }
            }
            return targets
        }
        scan(root, 0)
        return best
    }

    fun showStopControl() {
        if (stopButton != null) return
        val button = Button(this).apply {
            text = "停止任务"
            contentDescription = "立即停止 Vela 任务"
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(153, 44, 44))
            setOnClickListener { controller.stop() }
        }
        val density = resources.displayMetrics.density
        val parameters = WindowManager.LayoutParams(
            (112 * density).toInt(), (52 * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (96 * density).toInt()
        }
        windowManager.addView(button, parameters)
        stopButton = button
    }

    fun hideStopControl() {
        stopButton?.let { windowManager.removeView(it) }
        stopButton = null
    }
}

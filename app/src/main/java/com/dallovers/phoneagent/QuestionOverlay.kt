package com.dallovers.phoneagent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.graphics.Rect
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Human-only controls bound to one persisted question, independent of the foreground activity. */
internal class QuestionOverlay(private val service: AccessibilityService, private val controller: AgentController) {
    private val wm get() = service.getSystemService(WindowManager::class.java)
    private val ime get() = service.getSystemService(InputMethodManager::class.java)
    private var root: View? = null
    private var identity: Pair<String, String>? = null
    private var collapsed = false
    private var retryAt = 0L
    private var notBefore = 0L
    private var attachedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val reconcile = Runnable { render(controller.state.value) }
    val visible: Boolean get() = root?.let { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE &&
        it.getGlobalVisibleRect(Rect()) } == true
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun render(state: AgentState, rebuild: Boolean = false) {
        val session = state.pendingPhoneQuestion()
        val question = session?.question
        if (session == null || question == null) { clear(); controller.overlayStatus("没有待答问题"); return }
        handler.removeCallbacks(reconcile)
        val next = session.id to question.id
        if (identity != next || rebuild) {
            removeWindow()
            if (identity != next) collapsed = false
            identity = next
            retryAt = 0
        }
        if (!state.connected) { clear(); return }
        if (!service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            removeWindow()
            report(session, "等待解锁屏幕显示问题", "overlay_waiting_unlock")
            handler.postDelayed(reconcile, 3000)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (visible) {
            report(session, if (collapsed) "问题已收起，点浮层展开" else "待答浮层已显示", if (collapsed) "overlay_collapsed" else "overlay_shown")
            handler.postDelayed(reconcile, 2000)
            return
        }
        if (root != null) {
            if (now - attachedAt < 1200) {
                handler.postDelayed(reconcile, 250)
                return
            }
            removeWindow()
            retryAt = now + 2500
            report(session, "等待系统显示浮层，也可点通知回答", "overlay_not_visible")
        }
        if (now < retryAt) {
            handler.postDelayed(reconcile, retryAt - now)
            return
        }
        val dark = when (controller.appearance()) {
            "dark" -> true
            "light" -> false
            else -> service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val foreground = if (dark) Color.rgb(234, 237, 246) else Color.rgb(32, 38, 52)
        val background = if (dark) Color.rgb(42, 49, 65) else Color.rgb(254, 254, 255)
        val primary = if (dark) Color.rgb(164, 188, 248) else Color.rgb(73, 101, 172)
        fun label(text: String, size: Float = 16f) = TextView(service).apply {
            this.text = text; textSize = size; setTextColor(foreground)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fun button(text: String, action: () -> Unit) = Button(service).apply {
            this.text = text; textSize = 15f; isAllCaps = false; minimumHeight = dp(48)
            setTextColor(primary); backgroundTintList = ColorStateList.valueOf(if (dark) Color.rgb(52, 62, 82) else Color.rgb(231, 237, 250))
            filterTouchesWhenObscured = true
            setOnClickListener {
                if (SystemClock.uptimeMillis() >= notBefore && identity == next && current(next)) action()
            }
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10))
            elevation = dp(8).toFloat()
            this.background = GradientDrawable().apply { setColor(background); cornerRadius = dp(22).toFloat(); setStroke(dp(1), primary) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Vela 待答浮层"
        }
        if (collapsed) {
            card.addView(button("有个问题 · 点此回答") { collapsed = false; render(controller.state.value, true) })
        } else {
            val header = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(label("需要你确认一下", 17f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(button("收起") { collapsed = true; render(controller.state.value, true) })
            card.addView(header)
            val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
            val scroller = object : ScrollView(service) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val limit = (service.resources.displayMetrics.heightPixels * .55f).toInt()
                    val offered = View.MeasureSpec.getSize(heightMeasureSpec)
                    val max = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit else minOf(limit, offered)
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST))
                }
            }.apply { isFillViewport = false; addView(body) }
            card.addView(scroller)
            body.addView(label(session.title.take(80), 12f))
            body.addView(label(question.prompt, 18f))
            if (question.reason.isNotBlank()) {
                val reason = label(question.reason, 14f).apply { visibility = View.GONE }
                body.addView(button("为什么问我") { reason.visibility = if (reason.visibility == View.GONE) View.VISIBLE else View.GONE })
                body.addView(reason)
            }
            question.options.forEach { option ->
                body.addView(button(option) { submit(session.id, question.id, option) }, LinearLayout.LayoutParams(-1, -2))
            }
            val input = EditText(service).apply {
                hint = "也可以自己说…"; textSize = 16f; setTextColor(foreground); setHintTextColor(if (dark) Color.LTGRAY else Color.DKGRAY)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                filters = arrayOf(InputFilter.LengthFilter(4000)); minLines = 2; maxLines = 4
                setText(controller.loadDraft(session.id, question.id))
                visibility = View.GONE
            }
            val send = button("回复并继续") { submit(session.id, question.id, input.text.toString()) }.apply {
                visibility = View.GONE; isEnabled = input.text.isNotBlank()
            }
            body.addView(button("自己说") {
                input.visibility = View.VISIBLE; send.visibility = View.VISIBLE
                root?.let { view ->
                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    wm.updateViewLayout(view, params)
                }
                input.requestFocus()
                fun openKeyboard(attempt: Int) {
                    if (root !== card || !current(next)) return
                    if (!input.hasWindowFocus() && attempt < 10) {
                        input.postDelayed({ openKeyboard(attempt + 1) }, 80)
                    } else {
                        input.requestFocus()
                        ime.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        scroller.postDelayed({ if (root === card) scroller.fullScroll(View.FOCUS_DOWN) }, 250)
                    }
                }
                input.postDelayed({ openKeyboard(0) }, 120)
            })
            body.addView(input, LinearLayout.LayoutParams(-1, -2))
            body.addView(send, LinearLayout.LayoutParams(-1, -2))
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (current(next)) controller.saveDraft(session.id, question.id, s?.toString().orEmpty())
                    send.isEnabled = !s.isNullOrBlank()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            input.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE && input.text.isNotBlank()) {
                    if (SystemClock.uptimeMillis() >= notBefore) submit(session.id, question.id, input.text.toString())
                    true
                } else false
            }
            body.addView(button("暂停，稍后处理") { controller.dismissQuestion(session.id, question.id, "暂停") })
        }
        val width = minOf(service.resources.displayMetrics.widthPixels - dp(24), dp(420))
        val parameters = WindowManager.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(36)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { wm.addView(card, parameters) }.onSuccess {
            root = card
            attachedAt = SystemClock.uptimeMillis()
            notBefore = attachedAt + 350
            handler.postDelayed(reconcile, 250)
        }.onFailure {
            retryAt = SystemClock.uptimeMillis() + 3000
            report(session, "浮层尚未显示，可点通知回答", "overlay_failed")
            handler.postDelayed(reconcile, 3000)
        }
    }

    private fun report(session: TaskSession, message: String, event: String) =
        controller.questionWindowStatus(session, message, event)

    private fun current(id: Pair<String, String>): Boolean = controller.state.value.sessions.any {
        it.id == id.first && it.question?.id == id.second && it.status == SessionStatus.NEEDS_USER
    }
    private fun submit(sessionId: String, questionId: String, answer: String) {
        if (!controller.answerQuestion(sessionId, questionId, answer)) {
            Toast.makeText(service, "这个问题已处理，或答复未能保存，请再查看一下", Toast.LENGTH_SHORT).show()
        }
    }
    fun clear() {
        handler.removeCallbacks(reconcile)
        removeWindow()
        identity = null
        collapsed = false
        retryAt = 0
    }
    fun conceal() {
        removeWindow()
        render(controller.state.value)
    }
    fun screenAvailable() = render(controller.state.value)
    private fun removeWindow() {
        root?.let { view ->
            view.windowToken?.let { ime.hideSoftInputFromWindow(it, 0) }
            view.clearFocus()
            runCatching { wm.removeViewImmediate(view) }
        }
        root = null
    }
}

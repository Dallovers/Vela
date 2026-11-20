package com.dallovers.phoneagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class PracticeActivity : ComponentActivity() {
    private var sentCount = 0
    private var lastMessageLength = 0

    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        writer.println("practiceSent=$sentCount lastMessageLength=$lastMessageLength")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        val controller = (application as AgentApplication).controller
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            var message by rememberSaveable { mutableStateOf("") }
            var count by rememberSaveable { mutableIntStateOf(0) }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) AgentDarkColors else AgentColors) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("定位练习", style = MaterialTheme.typography.headlineLarge)
                        Text("这里的发送只更新练习计数，可用来检查按钮是否点得准。")
                        Text("已模拟发送 $count 次", style = MaterialTheme.typography.titleLarge)
                        Text(state.status)
                        val selected = state.sessions.firstOrNull { it.question != null }
                            ?: state.sessions.firstOrNull { it.id == state.selectedSessionId }
                        if (selected != null && selected.status in setOf(SessionStatus.PAUSED, SessionStatus.FAILED)) {
                            OutlinedButton(onClick = { controller.runSession(selected.id, controller.loadSettings()) },
                                enabled = !state.running, modifier = Modifier.fillMaxWidth()) { Text("重新观察并继续当前会话") }
                        }
                        if (state.running) OutlinedButton(onClick = { controller.stop("会话已暂停") },
                            modifier = Modifier.fillMaxWidth()) { Text("暂停当前会话") }
                        selected?.question?.let { question ->
                            var reply by rememberSaveable(question.id) { mutableStateOf("") }
                            Text("需要你决定：${question.prompt}", style = MaterialTheme.typography.titleMedium)
                            Text(question.reason)
                            question.options.forEach { option -> OutlinedButton(onClick = {
                                controller.answerQuestion(selected.id, question.id, option)
                            }, modifier = Modifier.fillMaxWidth()) { Text(option) } }
                            OutlinedTextField(reply, { reply = it }, label = { Text("也可以自己说") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { controller.answerQuestion(selected.id, question.id, reply) }, enabled = reply.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()) { Text("回复并继续") }
                            OutlinedButton(onClick = { controller.dismissQuestion(selected.id, question.id, "暂停") }, modifier = Modifier.fillMaxWidth()) { Text("暂停") }
                        }
                        OutlinedTextField(message, { message = it }, label = { Text("练习消息") }, modifier = Modifier.fillMaxWidth())
                        // Deliberately draw this local test button without an accessibility node,
                        // so the same test covers native input targeting and OCR-only button targeting.
                        AndroidView(factory = { context -> OcrPracticeButton(context) },
                            modifier = Modifier.fillMaxWidth().height(52.dp), update = { view ->
                                view.setOnClickListener {
                                    if (message.isNotBlank()) {
                                        count += 1
                                        sentCount = count
                                        lastMessageLength = message.length
                                        message = ""
                                    }
                                }
                            })
                        OutlinedButton(onClick = {
                            controller.start("在当前定位练习页的练习消息框填写“定位测试”，然后点击一次发送。当页面显示“已模拟发送 1 次”且输入框为空时结束。仅操作当前练习页面。",
                                controller.loadSettings(), fromHome = false)
                        }, enabled = !state.running && count == 0, modifier = Modifier.fillMaxWidth()) { Text("运行一次定位自测") }
                        OutlinedButton(onClick = { controller.createPracticeSession() },
                            enabled = !state.running && count == 0 && selected?.question == null,
                            modifier = Modifier.fillMaxWidth()) { Text("多任务问答与恢复自测") }
                        OutlinedButton(onClick = { controller.createOverlayAnswerPractice() },
                            enabled = !state.running && selected?.question == null, modifier = Modifier.fillMaxWidth()) { Text("跨应用浮层答复练习（不点击）") }
                        TextButton(onClick = { controller.stop(); finish() }, modifier = Modifier.fillMaxWidth()) { Text("返回助手") }
                    }
                }
            }
        }
    }
}

private class OcrPracticeButton(context: android.content.Context) : android.view.View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        paint.color = android.graphics.Color.rgb(66, 82, 150)
        val radius = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("发送", width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}

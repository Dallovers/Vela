package com.dallovers.phoneagent

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Foreground-only read so Android's clipboard privacy rule is respected. Result contains a URL only. */
internal object CopiedLinks {
    private var pending: CompletableDeferred<String>? = null
    private var token: String = ""
    private var notBefore: Long = 0
    fun requested(text: String): Boolean = ("复制" in text && ("链接" in text || "连接" in text)) ||
        (Regex("\\bcopy\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && Regex("\\b(link|url)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
    fun isCopyTap(action: AgentAction, screen: ScreenObservation?): Boolean {
        if (action.kind !in setOf("tap", "tap_element")) return false
        val label = screen?.targets?.firstOrNull { it.id == action.targetId }?.label.orEmpty()
        return listOf(label, action.reason).any { requested(it) || "分享链接" in it }
    }
    fun extract(text: String, timestamp: Long, startedAt: Long): String {
        require(startedAt > 0 && timestamp >= startedAt) { "剪贴板不是本次任务复制的内容，请重新核对复制动作" }
        val url = Regex("https?://[^\\s<>\"，。；）)]+").find(text.take(8000))?.value
            ?: error("剪贴板里没有可核对的网页链接，不能认定复制成功")
        require(url.length <= 2000 && !java.net.URI(url).host.isNullOrBlank()) { "复制的链接格式或长度无法核对" }
        return url
    }
    suspend fun read(context: Context, startedAt: Long): String {
        check(pending == null) { "正在核对复制结果" }
        val request = CompletableDeferred<String>()
        pending = request; token = java.util.UUID.randomUUID().toString(); notBefore = startedAt
        try {
            context.startActivity(Intent(context, ClipboardProbeActivity::class.java).putExtra("token", token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY))
            return withTimeout(6000) { request.await() }
        } finally { if (pending === request) { pending = null; token = "" } }
    }
    fun collect(activity: Activity, suppliedToken: String) {
        val request = pending ?: return
        if (suppliedToken != token) return
        runCatching {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            val clip = clipboard.primaryClip ?: error("没有读取到复制内容")
            extract(clip.getItemAt(0).text?.toString().orEmpty(), clipboard.primaryClipDescription?.timestamp ?: 0, notBefore)
        }.onSuccess { request.complete(it) }.onFailure { request.completeExceptionally(it) }
    }
}

class ClipboardProbeActivity : Activity() {
    private var collected = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "正在核对刚复制的链接…"; textSize = 17f; setPadding(40, 40, 40, 40) })
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 5000)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !collected) { collected = true
            window.decorView.postDelayed({ CopiedLinks.collect(this, intent.getStringExtra("token").orEmpty()); finish() }, 180)
        }
    }
}

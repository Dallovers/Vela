package com.dallovers.phoneagent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 只投递两类「需要你看一眼」的通知：待答问题、任务跑完。
 * 权限、系统通知开关、渠道被关这三道检查两类共用，失败原因一律回给调用方记进运行日志。
 */
class AgentNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun showQuestion(sessionId: String, urgent: Boolean): String {
        val channelId = if (urgent) "question_fallback" else "questions"
        blocked(channelId, if (urgent) "待答浮层补充提醒" else "任务待答提醒",
            if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)?.let { return it }
        val intent = Intent(context, MainActivity::class.java).putExtra("questionSessionId", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return post(41, channelId, "Vela 需要你的答复", "点这里打开待答会话。", intent, alertOnce = !urgent)
    }

    /**
     * 一条会话的结果。完成和「没做完」共用同一个通知位（42）和同一个渠道：
     * 对用户来说都是「这件事有结果了」，连着跑几个只留最新一条，不堆一列。
     */
    fun showCompleted(sessionId: String, title: String, mode: ConversationMode): String =
        showResult(sessionId, TaskNoticeRules.completedTitle(title), TaskNoticeRules.body(mode))

    fun showInterrupted(sessionId: String, title: String, reason: String): String =
        showResult(sessionId, TaskNoticeRules.interruptedTitle(title), TaskNoticeRules.interruptText(reason))

    private fun showResult(sessionId: String, title: String, text: String): String {
        blocked("completions", "任务结果提醒", NotificationManager.IMPORTANCE_DEFAULT)?.let { return it }
        val intent = Intent(context, MainActivity::class.java).putExtra("sessionId", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return post(42, "completions", title, text, intent, alertOnce = false)
    }

    fun clearQuestion() { manager.cancel(41) }
    fun clearCompleted() { manager.cancel(42) }
    fun clearAll() { clearQuestion(); clearCompleted() }

    private fun blocked(channelId: String, name: String, importance: Int): String? {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "permission_denied"
        if (!manager.areNotificationsEnabled()) return "notifications_disabled"
        manager.createNotificationChannel(NotificationChannel(channelId, name, importance))
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return "channel_disabled"
        return null
    }

    private fun post(id: Int, channelId: String, title: String, text: String, intent: Intent, alertOnce: Boolean): String = try {
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_agent)
            .setContentTitle(title).setContentText(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(alertOnce).setContentIntent(pending).build())
        "posted"
    } catch (_: SecurityException) {
        "permission_denied"
    }
}

/** 什么时候提醒、提醒时说什么，全是纯判断，单独拆出来是为了能直接单测。 */
internal object TaskNoticeRules {
    /** 只有「跑着跑着跑完了」才提醒。用户自己停、被问题拦下、改需求换掉旧任务，都不提醒。 */
    fun justFinished(previous: SessionStatus, next: SessionStatus): Boolean =
        previous == SessionStatus.RUNNING && next == SessionStatus.COMPLETED

    /** 正看着这个对话就不用打扰；切到别的对话或者退到后台才提醒。关掉开关后一律不提醒。 */
    fun shouldPost(enabled: Boolean, foreground: Boolean, selectedSessionId: String?, sessionId: String): Boolean =
        enabled && !(foreground && selectedSessionId == sessionId)

    fun completedTitle(sessionTitle: String): String = "「${summary(sessionTitle)}」已完成"

    fun interruptedTitle(sessionTitle: String): String = "「${summary(sessionTitle)}」没做完"

    /** 停下来的原因就是错误信息，压成一行、截断，避免通知栏里半行字。 */
    fun interruptText(reason: String): String =
        reason.replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "点这里看原因。" }

    fun body(mode: ConversationMode): String =
        if (mode == ConversationMode.CHAT) "点这里看回复。" else "点这里看执行结果。"

    fun summary(sessionTitle: String): String =
        sessionTitle.replace(Regex("\\s+"), " ").trim().take(24).ifBlank { "这个对话" }
}

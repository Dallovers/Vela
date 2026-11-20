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

class QuestionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun show(sessionId: String, urgent: Boolean): String {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "permission_denied"
        if (!manager.areNotificationsEnabled()) return "notifications_disabled"
        val channelId = if (urgent) "question_fallback" else "questions"
        val importance = if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(NotificationChannel(channelId,
            if (urgent) "待答浮层补充提醒" else "任务待答提醒", importance))
        if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return "channel_disabled"
        val intent = Intent(context, MainActivity::class.java).putExtra("questionSessionId", sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 41, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(41, Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("Vela 需要你的答复").setContentText("点这里打开待答会话。")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(!urgent).setContentIntent(pending).build())
            "posted"
        } catch (_: SecurityException) {
            "permission_denied"
        }
    }
    fun clear() { manager.cancel(41) }
}

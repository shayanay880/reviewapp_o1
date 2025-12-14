package com.example.v12

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class DueAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lessonId = intent.getLongExtra("lessonId", 0L)
        val title = intent.getStringExtra("title") ?: "Lesson"
        val channelId = "reviews_due"

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Reviews", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val openIntent = Intent(context, MainActivity::class.java)
        val contentPI = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Review due")
            .setContentText(title)
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .build()

        val notificationId = (lessonId % Int.MAX_VALUE).toInt().takeIf { it != 0 } ?: title.hashCode()
        nm.notify(notificationId, n)
    }
}

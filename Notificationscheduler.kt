package com.example.v12

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object NotificationScheduler {

    @SuppressLint("ScheduleExactAlarm")
    fun schedule(context: Context, lessonId: Long, title: String, dueAt: Long) {
        val now = System.currentTimeMillis()
        if (dueAt <= now) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, DueAlarmReceiver::class.java).apply {
            putExtra("lessonId", lessonId)
            putExtra("title", title)
        }

        val requestCode = (lessonId % Int.MAX_VALUE).toInt()

        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pi)
    }

    fun cancel(context: Context, lessonId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DueAlarmReceiver::class.java)

        val requestCode = (lessonId % Int.MAX_VALUE).toInt()

        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}

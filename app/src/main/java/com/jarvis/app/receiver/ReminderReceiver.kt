package com.jarvis.app.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.app.JarvisApplication
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.app.service.JarvisForegroundService

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1)
        val text = intent.getStringExtra(EXTRA_REMINDER_TEXT) ?: return

        // Show notification
        showReminderNotification(context, text)

        // Speak the reminder via the foreground service
        val serviceIntent = Intent(context, JarvisForegroundService::class.java).apply {
            action = JarvisForegroundService.ACTION_PROCESS_TEXT
            putExtra(JarvisForegroundService.EXTRA_TEXT, "Boss, আপনার রিমাইন্ডার: $text")
        }
        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            // Service not running, just show notification
        }
    }

    private fun showReminderNotification(context: Context, text: String) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, JarvisApplication.CHANNEL_REMINDER)
            .setContentTitle("JARVIS Reminder")
            .setContentText("Boss, $text")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(REMINDER_NOTIFICATION_BASE + System.currentTimeMillis().toInt() % 10000, notification)
    }

    companion object {
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
        private const val REMINDER_NOTIFICATION_BASE = 5000
    }
}

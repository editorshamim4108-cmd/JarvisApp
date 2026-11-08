package com.jarvis.app.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class JarvisNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isEnabled) return

        val pkg = sbn.packageName ?: return
        if (pkg == "com.jarvis.app") return

        if (pkg !in MONITORED_APPS) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE)?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        if (title.isNullOrBlank() || text.isNullOrBlank()) return

        val key = "$pkg:$title:${text.take(50)}"
        val now = System.currentTimeMillis()
        if (key == lastNotificationKey && now - lastNotificationTime < 5000) return
        lastNotificationKey = key
        lastNotificationTime = now

        val appName = APP_NAMES[pkg] ?: "App"

        lastSender = title
        lastMessage = text
        lastApp = appName

        try {
            val svcIntent = Intent(this, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_NOTIFICATION
                putExtra("app_name", appName)
                putExtra("sender", title)
                putExtra("message", text)
            }
            startService(svcIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify service", e)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isEnabled = true
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        isEnabled = false
    }

    companion object {
        private const val TAG = "JarvisNotifListener"
        var instance: JarvisNotificationListener? = null
        var isEnabled = false
        var lastNotificationKey: String? = null
        var lastNotificationTime = 0L
        var lastSender: String? = null
        var lastMessage: String? = null
        var lastApp: String? = null

        private val MONITORED_APPS = setOf(
            "com.whatsapp",
            "com.facebook.orca",
            "org.telegram.messenger",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.instagram.android",
            "com.google.android.gm",
            "com.viber.voip",
            "com.imo.android.imoim",
            "com.facebook.katana",
        )

        private val APP_NAMES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.facebook.orca" to "Messenger",
            "org.telegram.messenger" to "Telegram",
            "com.samsung.android.messaging" to "SMS",
            "com.google.android.apps.messaging" to "SMS",
            "com.instagram.android" to "Instagram",
            "com.google.android.gm" to "Gmail",
            "com.viber.voip" to "Viber",
            "com.imo.android.imoim" to "IMO",
            "com.facebook.katana" to "Facebook",
        )
    }
}

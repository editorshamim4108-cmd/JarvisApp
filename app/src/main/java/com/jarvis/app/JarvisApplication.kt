package com.jarvis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jarvis.app.data.AppDatabase

class JarvisApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_FOREGROUND,
            "JARVIS Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps JARVIS always listening in the background"
            setShowBadge(false)
        }

        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDER,
            "JARVIS Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminder alerts from JARVIS"
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(reminderChannel)
    }

    companion object {
        const val CHANNEL_FOREGROUND = "jarvis_foreground"
        const val CHANNEL_REMINDER = "jarvis_reminder"

        lateinit var instance: JarvisApplication
            private set
    }
}

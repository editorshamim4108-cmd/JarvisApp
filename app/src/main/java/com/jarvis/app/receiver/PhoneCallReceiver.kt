package com.jarvis.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.app.service.JarvisForegroundService

class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                val name = if (number != null) lookupContact(context, number) else null

                pendingNumber = number
                pendingName = name ?: number
                isRinging = true

                try {
                    val svcIntent = Intent(context, JarvisForegroundService::class.java).apply {
                        action = JarvisForegroundService.ACTION_INCOMING_CALL
                        putExtra("caller_name", pendingName ?: "Unknown")
                        putExtra("caller_number", number ?: "")
                    }
                    context.startService(svcIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to notify service", e)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                isRinging = false
                pendingNumber = null
                pendingName = null
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
            }
        }
    }

    private fun lookupContact(context: Context, number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "PhoneCallReceiver"
        var pendingNumber: String? = null
        var pendingName: String? = null
        var isRinging = false
    }
}

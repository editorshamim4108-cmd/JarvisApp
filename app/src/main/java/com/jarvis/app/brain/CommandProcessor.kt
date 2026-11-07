package com.jarvis.app.brain

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.ContactsContract
import android.util.Log
import com.jarvis.app.data.AppDatabase
import com.jarvis.app.data.ConversationMessage
import com.jarvis.app.data.Reminder
import com.jarvis.app.data.ReminderManager
import com.jarvis.app.service.JarvisAccessibilityService
import com.jarvis.app.service.JarvisForegroundService
import com.jarvis.app.service.OverlayService
import com.jarvis.app.settings.SettingsManager
import com.jarvis.app.voice.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommandProcessor(private val context: Context) {

    private val aiBrain = AiBrain()
    private val voiceEngine = VoiceEngine(context)
    private val settings = SettingsManager(context)
    private val db = AppDatabase.getInstance(context)
    private val reminderManager = ReminderManager(context)

    // Common app package names
    private val appPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "instagram" to "com.instagram.android",
        "ইনস্টাগ্রাম" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "camera" to "com.android.camera",
        "ক্যামেরা" to "com.android.camera",
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "telegram" to "org.telegram.messenger",
        "টেলিগ্রাম" to "org.telegram.messenger",
        "maps" to "com.google.android.apps.maps",
        "ম্যাপ" to "com.google.android.apps.maps",
        "calculator" to "com.google.android.calculator",
        "ক্যালকুলেটর" to "com.google.android.calculator",
        "gallery" to "com.google.android.apps.photos",
        "গ্যালারি" to "com.google.android.apps.photos",
        "clock" to "com.google.android.deskclock",
        "ঘড়ি" to "com.google.android.deskclock",
        "messenger" to "com.facebook.orca",
        "মেসেঞ্জার" to "com.facebook.orca",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "টিকটক" to "com.zhiliaoapp.musically",
        "spotify" to "com.spotify.music",
        "স্পটিফাই" to "com.spotify.music",
    )

    suspend fun process(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return
        val lower = input.lowercase()

        // Save user message
        saveMessage("user", input)

        try {
            when {
                // 1. Stop / Sleep commands
                isStopCommand(lower) -> {
                    reply("ঠিক আছে Boss, আমি নীরব মোডে যাচ্ছি।")
                    JarvisForegroundService.instance?.stopSpeechRecognition()
                }

                // 2. Wake word acknowledgment
                isJustWakeWord(lower) -> {
                    reply("জি Boss, বলুন?")
                }

                // 3. Presence check
                isPresenceCheck(lower) -> {
                    reply("জি Boss, আমি এখানে আছি। বলুন কী করতে হবে?")
                }

                // 4. App open commands
                isAppOpenCommand(lower) -> {
                    handleAppOpen(input, lower)
                }

                // 5. Screen reading + translation
                isScreenReadCommand(lower) -> {
                    handleScreenRead(lower)
                }

                // 6. Volume control
                isVolumeCommand(lower) -> {
                    handleVolume(lower)
                }

                // 7. Contact save
                isContactSaveCommand(lower) -> {
                    handleContactSave(input, lower)
                }

                // 8. Reminder
                isReminderCommand(lower) -> {
                    handleReminder(input, lower)
                }

                // 9. Reminder acknowledgment
                isReminderAck(lower) -> {
                    handleReminderAck()
                }

                // 10. Navigation commands
                isNavCommand(lower) -> {
                    handleNavigation(lower)
                }

                // 11. Type text command
                isTypeCommand(lower) -> {
                    handleTypeText(input, lower)
                }

                // 12. Click / Tap command
                isClickCommand(lower) -> {
                    handleClick(input, lower)
                }

                // 13. Phone lock
                isLockCommand(lower) -> {
                    reply("Boss, ফোনের স্ক্রিন লক করে দিচ্ছি।")
                    JarvisAccessibilityService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    )
                }

                // 14. General AI question
                else -> {
                    handleAiQuestion(input)
                }
            }
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Error processing command", e)
            reply("Boss, একটু সমস্যা হচ্ছে। আবার চেষ্টা করুন।")
        }
    }

    // --- Intent Detection ---

    private fun isStopCommand(lower: String): Boolean {
        return lower.contains("অফ হয়ে যাও") || lower.contains("ঘুমিয়ে পড়ো") ||
                lower.contains("stop listening") || lower.contains("চুপ থাকো") ||
                lower.contains("বন্ধ হও") || lower.contains("নীরব")
    }

    private fun isJustWakeWord(lower: String): Boolean {
        return lower in listOf("hey jarvis", "হেই জার্ভিস", "জার্ভিস", "jarvis", "হে জার্ভিস")
    }

    private fun isPresenceCheck(lower: String): Boolean {
        return lower.contains("তুমি আছো") || lower.contains("তুমি কি আছো") ||
                lower == "আছো?" || lower.contains("are you there")
    }

    private fun isAppOpenCommand(lower: String): Boolean {
        return lower.contains("ওপেন কর") || lower.contains("খোলো") ||
                lower.contains("খুলো") || lower.startsWith("open ") ||
                lower.contains("চালু কর") || lower.contains("চালাও")
    }

    private fun isScreenReadCommand(lower: String): Boolean {
        val hasScreen = lower.contains("স্ক্রিন") || lower.contains("screen") ||
                lower.contains("টেক্সট") || lower.contains("লেখা")
        val hasAction = lower.contains("পড়") || lower.contains("read") ||
                lower.contains("বল") || lower.contains("translate") ||
                lower.contains("অনুবাদ") || lower.contains("বাংলায়") ||
                lower.contains("ইংলিশে") || lower.contains("ইংরেজিতে")
        return hasScreen && hasAction
    }

    private fun isVolumeCommand(lower: String): Boolean {
        return lower.contains("volume") || lower.contains("ভলিউম") ||
                lower.contains("সাউন্ড") || lower.contains("sound") ||
                lower.contains("আওয়াজ")
    }

    private fun isContactSaveCommand(lower: String): Boolean {
        return (lower.contains("কন্টাক্ট") || lower.contains("contact") ||
                lower.contains("নাম্বার") || lower.contains("number")) &&
                (lower.contains("সেভ") || lower.contains("save") ||
                        lower.contains("অ্যাড") || lower.contains("add") ||
                        lower.contains("যোগ"))
    }

    private fun isReminderCommand(lower: String): Boolean {
        return lower.contains("মনে কর") || lower.contains("রিমাইন্ডার") ||
                lower.contains("remind") || lower.contains("মনে রাখ") ||
                lower.contains("মনে করাই") || lower.contains("পরে বল")
    }

    private fun isReminderAck(lower: String): Boolean {
        return lower == "ঠিক আছে" || lower == "হ্যাঁ ঠিক আছে" ||
                lower == "ok" || lower == "okay" || lower == "done" ||
                lower == "হয়ে গেছে" || lower == "বুঝেছি"
    }

    private fun isNavCommand(lower: String): Boolean {
        return lower.contains("ব্যাক") || lower.contains("back") ||
                lower.contains("হোম") || lower.contains("home") ||
                lower.contains("স্ক্রল") || lower.contains("scroll") ||
                lower.contains("নোটিফিকেশন") || lower.contains("notification")
    }

    private fun isTypeCommand(lower: String): Boolean {
        return lower.contains("লিখো") || lower.contains("লেখো") ||
                lower.contains("type ") || lower.contains("টাইপ কর")
    }

    private fun isClickCommand(lower: String): Boolean {
        return lower.contains("ক্লিক") || lower.contains("click") ||
                lower.contains("ট্যাপ") || lower.contains("tap") ||
                lower.contains("চাপ") || lower.contains("press")
    }

    private fun isLockCommand(lower: String): Boolean {
        return lower.contains("লক কর") || lower.contains("lock") ||
                lower.contains("ফোন লক")
    }

    // --- Handlers ---

    private suspend fun handleAppOpen(input: String, lower: String) {
        val appName = extractAppName(input, lower)
        val packageName = findPackageName(appName)

        if (packageName != null) {
            val success = JarvisAccessibilityService.instance?.openApp(packageName)
                ?: run {
                    // Fallback: use Intent directly
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            true
                        } else false
                    } catch (e: Exception) { false }
                }
            if (success) {
                reply("$appName ওপেন করেছি Boss। এখন কী করব?")
            } else {
                reply("Boss, $appName ওপেন করতে পারলাম না। অ্যাপটি ইনস্টল আছে কিনা দেখুন।")
            }
        } else {
            reply("Boss, $appName নামের অ্যাপটি চিনতে পারছি না। পুরো নামটা বলুন।")
        }
    }

    private fun extractAppName(input: String, lower: String): String {
        val patterns = listOf(
            "ওপেন করো ", "ওপেন কর ", "খোলো ", "খুলো ", "চালু কর ", "চালাও ",
            "open ", "ওপেন করো", "ওপেন কর", "খোলো", "খুলো", "চালু কর", "চালাও"
        )
        var cleaned = input
        for (p in patterns) {
            cleaned = cleaned.replace(p, "", ignoreCase = true)
        }
        return cleaned.trim().ifEmpty { input }
    }

    private fun findPackageName(appName: String): String? {
        val lower = appName.lowercase().trim()
        // Direct match
        appPackages[lower]?.let { return it }
        // Partial match
        for ((key, pkg) in appPackages) {
            if (lower.contains(key) || key.contains(lower)) {
                return pkg
            }
        }
        // Try to find via PackageManager
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = context.packageManager.queryIntentActivities(intent, 0)
            for (app in apps) {
                val label = app.loadLabel(context.packageManager).toString().lowercase()
                if (label.contains(lower) || lower.contains(label)) {
                    return app.activityInfo.packageName
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun handleScreenRead(lower: String) {
        val screenText = JarvisAccessibilityService.currentScreenText
        if (screenText.isBlank()) {
            reply("Boss, স্ক্রিনে কোনো টেক্সট পাচ্ছি না।")
            return
        }

        val wantsEnglish = lower.contains("ইংলিশে") || lower.contains("ইংরেজিতে") ||
                lower.contains("english")
        val wantsHindi = lower.contains("হিন্দিতে") || lower.contains("hindi")

        val targetLang = when {
            wantsEnglish -> "en"
            wantsHindi -> "hi"
            else -> "bn"
        }

        val translated = translateText(screenText, targetLang)
        if (translated != null) {
            val prefix = when (targetLang) {
                "en" -> "Boss, your screen says:"
                "hi" -> "Boss, आपकी स्क्रीन पर लिखा है:"
                else -> "Boss, আপনার স্ক্রিনে লেখা আছে:"
            }
            reply("$prefix $translated")
        } else {
            reply("Boss, আপনার স্ক্রিনে লেখা আছে: $screenText")
        }
    }

    private suspend fun translateText(text: String, targetLang: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://translate.googleapis.com/translate_a/single?" +
                        "client=gtx&sl=auto&tl=$targetLang&dt=t&q=${java.net.URLEncoder.encode(text, "UTF-8")}"
                val request = okhttp3.Request.Builder().url(url).get().build()
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = org.json.JSONArray(body)
                    val segments = json.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        val seg = segments.optJSONArray(i)
                        if (seg != null && seg.length() > 0) {
                            sb.append(seg.optString(0, ""))
                        }
                    }
                    sb.toString().trim().ifEmpty { null }
                } else null
            } catch (e: Exception) {
                Log.e("CommandProcessor", "Translation error", e)
                null
            }
        }
    }

    private fun handleVolume(lower: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        when {
            lower.contains("বাড়াও") || lower.contains("বাড়া") || lower.contains("up") ||
                    lower.contains("increase") || lower.contains("বেশি") -> {
                val newVol = (current + max / 5).coerceAtMost(max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম বাড়িয়ে দিয়েছি Boss।")
            }
            lower.contains("কমাও") || lower.contains("কমা") || lower.contains("down") ||
                    lower.contains("decrease") || lower.contains("কম") -> {
                val newVol = (current - max / 5).coerceAtLeast(0)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম কমিয়ে দিয়েছি Boss।")
            }
            lower.contains("মিউট") || lower.contains("mute") || lower.contains("নিঃশব্দ") -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                reply("সাউন্ড মিউট করে দিয়েছি Boss।")
            }
            lower.contains("ফুল") || lower.contains("full") || lower.contains("সর্বোচ্চ") || lower.contains("max") -> {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম সর্বোচ্চে দিয়ে দিয়েছি Boss।")
            }
            else -> {
                reply("Boss, ভলিউম কতটুকু করতে চান? বাড়াও, কমাও, মিউট বা ফুল বলুন।")
            }
        }
    }

    private suspend fun handleContactSave(input: String, lower: String) {
        // Extract phone number from input or screen
        val numberRegex = Regex("[+]?[0-9\\s\\-]{7,15}")
        var number = numberRegex.find(input)?.value?.replace(Regex("[\\s\\-]"), "")

        if (number == null) {
            // Try to find number from screen text
            val screenText = JarvisAccessibilityService.currentScreenText
            number = numberRegex.find(screenText)?.value?.replace(Regex("[\\s\\-]"), "")
        }

        if (number == null) {
            reply("Boss, কোনো নাম্বার পাচ্ছি না। নাম্বারটা বলুন।")
            // Set a flag to expect a number next — simplified here
            return
        }

        reply("Boss, নাম্বার পেয়েছি: $number। কন্টাক্টের নাম কী দেব?")
        // The next voice input will be treated as the contact name
        // For now, save with a placeholder or wait for next input
        pendingContactNumber = number
    }

    private suspend fun handleReminder(input: String, lower: String) {
        // Parse time duration
        val durationMs = parseTimeDuration(lower)
        val reminderText = extractReminderText(input)

        if (durationMs > 0 && reminderText.isNotEmpty()) {
            val triggerTime = System.currentTimeMillis() + durationMs
            val reminder = Reminder(
                text = reminderText,
                triggerTimeMillis = triggerTime,
                isAcknowledged = false,
                createdAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) {
                db.reminderDao().insert(reminder)
            }
            reminderManager.scheduleReminder(reminder)

            val timeDesc = formatDuration(durationMs)
            reply("Done Boss! $timeDesc পরে মনে করিয়ে দেব: \"$reminderText\"")
        } else if (reminderText.isNotEmpty()) {
            // Default to 1 hour if no time specified
            val triggerTime = System.currentTimeMillis() + 3600_000L
            val reminder = Reminder(
                text = reminderText,
                triggerTimeMillis = triggerTime,
                isAcknowledged = false,
                createdAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) {
                db.reminderDao().insert(reminder)
            }
            reminderManager.scheduleReminder(reminder)
            reply("Boss, ১ ঘণ্টা পরে মনে করিয়ে দেব: \"$reminderText\"। সময় ঠিক না হলে বলুন।")
        } else {
            reply("Boss, কী মনে করাইতে চান আর কখন — দুটোই বলুন।")
        }
    }

    private fun parseTimeDuration(lower: String): Long {
        var totalMs = 0L

        // Minutes
        val minRegex = Regex("(\\d+)\\s*(মিনিট|minute|min)")
        minRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(60_000L) ?: 0
        }

        // Hours
        val hourRegex = Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hour|hr)")
        hourRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(3600_000L) ?: 0
        }

        // Days
        val dayRegex = Regex("(\\d+)\\s*(দিন|day)")
        dayRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(86_400_000L) ?: 0
        }

        // Weeks
        val weekRegex = Regex("(\\d+)\\s*(সপ্তাহ|week)")
        weekRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(604_800_000L) ?: 0
        }

        // Bengali number words
        if (lower.contains("এক সপ্তাহ") || lower.contains("১ সপ্তাহ") || lower.contains("1 week")) {
            totalMs = 604_800_000L
        }
        if (lower.contains("আধা ঘণ্টা") || lower.contains("৩০ মিনিট")) {
            totalMs = 1_800_000L
        }
        if (lower.contains("আগামীকাল") || lower.contains("tomorrow")) {
            totalMs = 86_400_000L
        }

        return totalMs
    }

    private fun extractReminderText(input: String): String {
        val patterns = listOf(
            "মনে করাইও ", "মনে করিও ", "মনে রাখো ", "মনে করাই দিও ",
            "remind me ", "reminder ", "মনে করাও ", "পরে বলো "
        )
        var text = input
        for (p in patterns) {
            text = text.replace(p, "", ignoreCase = true)
        }
        // Remove time parts
        text = text.replace(Regex("(\\d+\\s*(সপ্তাহ|দিন|ঘণ্টা|ঘন্টা|মিনিট|week|day|hour|minute|min|hr)\\s*পরে?\\s*)"), "")
        text = text.replace(Regex("(এক সপ্তাহ পরে?|আগামীকাল|আধা ঘণ্টা পরে?)\\s*"), "")
        return text.replace(Regex("^[—\\-–:]+"), "").trim()
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms >= 604_800_000 -> "${ms / 604_800_000} সপ্তাহ"
            ms >= 86_400_000 -> "${ms / 86_400_000} দিন"
            ms >= 3_600_000 -> "${ms / 3_600_000} ঘণ্টা"
            ms >= 60_000 -> "${ms / 60_000} মিনিট"
            else -> "কিছুক্ষণ"
        }
    }

    private suspend fun handleReminderAck() {
        withContext(Dispatchers.IO) {
            val active = db.reminderDao().getActiveReminders()
            // Mark the most recent triggered reminder as acknowledged
            val triggered = active.filter { it.triggerTimeMillis <= System.currentTimeMillis() }
            for (r in triggered) {
                db.reminderDao().markAcknowledged(r.id)
                db.reminderDao().deleteAcknowledged()
            }
        }
        reply("ঠিক আছে Boss, রিমাইন্ডার মুছে দিয়েছি।")
    }

    private fun handleNavigation(lower: String) {
        val service = JarvisAccessibilityService.instance
        when {
            lower.contains("ব্যাক") || lower.contains("back") -> {
                service?.pressBack()
                reply("ব্যাক গেছি Boss।")
            }
            lower.contains("হোম") || lower.contains("home") -> {
                service?.pressHome()
                reply("হোম স্ক্রিনে এসেছি Boss।")
            }
            lower.contains("নোটিফিকেশন") || lower.contains("notification") -> {
                service?.openNotifications()
                reply("নোটিফিকেশন প্যানেল খুলেছি Boss।")
            }
            lower.contains("স্ক্রল আপ") || lower.contains("scroll up") || lower.contains("উপরে") -> {
                service?.scrollUp()
                reply("উপরে স্ক্রল করেছি Boss।")
            }
            lower.contains("স্ক্রল") || lower.contains("scroll") || lower.contains("নিচে") -> {
                service?.scrollDown()
                reply("নিচে স্ক্রল করেছি Boss।")
            }
        }
    }

    private fun handleTypeText(input: String, lower: String) {
        val patterns = listOf("লিখো ", "লেখো ", "টাইপ করো ", "টাইপ কর ", "type ")
        var textToType = input
        for (p in patterns) {
            textToType = textToType.replace(p, "", ignoreCase = true)
        }
        textToType = textToType.trim().removeSurrounding("\"").removeSurrounding("'")

        if (textToType.isNotEmpty()) {
            val success = JarvisAccessibilityService.instance?.performTypeText(textToType) ?: false
            if (success) {
                reply("লিখে দিয়েছি Boss।")
            } else {
                reply("Boss, টেক্সট ফিল্ড পাচ্ছি না। আগে যেখানে লিখতে চান সেখানে ট্যাপ করুন।")
            }
        } else {
            reply("Boss, কী লিখব বলুন।")
        }
    }

    private fun handleClick(input: String, lower: String) {
        val patterns = listOf("ক্লিক করো ", "ক্লিক কর ", "ট্যাপ করো ", "ট্যাপ কর ", "চাপো ", "press ", "click ", "tap ")
        var target = input
        for (p in patterns) {
            target = target.replace(p, "", ignoreCase = true)
        }
        target = target.trim().removeSurrounding("\"").removeSurrounding("'")

        if (target.isNotEmpty()) {
            val success = JarvisAccessibilityService.instance?.performClickOnText(target) ?: false
            if (success) {
                reply("\"$target\" এ ক্লিক করেছি Boss।")
            } else {
                reply("Boss, \"$target\" খুঁজে পাচ্ছি না স্ক্রিনে।")
            }
        } else {
            reply("Boss, কোথায় ক্লিক করব বলুন।")
        }
    }

    private suspend fun handleAiQuestion(input: String) {
        // Update overlay
        updateOverlay("Thinking...")

        val screenContext = JarvisAccessibilityService.currentScreenText
        val response = aiBrain.ask(input, screenContext, settings.getLanguage())

        if (response.isNotEmpty()) {
            reply(response)
        } else {
            reply("Boss, উত্তর আনতে সমস্যা হচ্ছে। ইন্টারনেট চেক করুন।")
        }
    }

    private suspend fun reply(text: String) {
        saveMessage("assistant", text)
        JarvisForegroundService.lastReplyText = text
        JarvisForegroundService.replyListeners.forEach { it(text) }
        updateOverlay(text.take(60) + if (text.length > 60) "..." else "")
        voiceEngine.speak(text)
    }

    private fun updateOverlay(text: String) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_TEXT
            putExtra(OverlayService.EXTRA_TEXT, text)
        }
        try { context.startService(intent) } catch (_: Exception) {}
    }

    private suspend fun saveMessage(role: String, content: String) {
        withContext(Dispatchers.IO) {
            db.messageDao().insert(
                ConversationMessage(
                    role = role,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    companion object {
        var pendingContactNumber: String? = null
    }
}

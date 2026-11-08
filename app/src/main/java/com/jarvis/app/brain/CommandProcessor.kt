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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class CommandProcessor(private val context: Context) {

    private val aiBrain = AiBrain()
    private val voiceEngine = VoiceEngine(context)
    private val settings = SettingsManager(context)
    private val db = AppDatabase.getInstance(context)
    private val reminderManager = ReminderManager(context)

    private val appPackages = mapOf(
        // WhatsApp variants
        "whatsapp" to "com.whatsapp",
        "whats app" to "com.whatsapp",
        "watts app" to "com.whatsapp",
        "whatapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "হোয়াটস অ্যাপ" to "com.whatsapp",
        "ওয়াটসঅ্যাপ" to "com.whatsapp",
        "ওয়াটস অ্যাপ" to "com.whatsapp",
        // YouTube
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        // Facebook
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        // Instagram
        "instagram" to "com.instagram.android",
        "ইনস্টাগ্রাম" to "com.instagram.android",
        "ইন্সটা" to "com.instagram.android",
        // Chrome
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        // Samsung Camera
        "camera" to "com.sec.android.app.camera",
        "ক্যামেরা" to "com.sec.android.app.camera",
        "samsung camera" to "com.sec.android.app.camera",
        // Settings
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "সেটিং" to "com.android.settings",
        // Telegram
        "telegram" to "org.telegram.messenger",
        "টেলিগ্রাম" to "org.telegram.messenger",
        // Maps
        "maps" to "com.google.android.apps.maps",
        "ম্যাপ" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "গুগল ম্যাপ" to "com.google.android.apps.maps",
        // Samsung Calculator
        "calculator" to "com.sec.android.app.popupcalculator",
        "ক্যালকুলেটর" to "com.sec.android.app.popupcalculator",
        "samsung calculator" to "com.sec.android.app.popupcalculator",
        // Samsung Gallery
        "gallery" to "com.sec.android.gallery3d",
        "গ্যালারি" to "com.sec.android.gallery3d",
        "samsung gallery" to "com.sec.android.gallery3d",
        // Google Photos
        "photos" to "com.google.android.apps.photos",
        "ফটোস" to "com.google.android.apps.photos",
        "google photos" to "com.google.android.apps.photos",
        // Samsung Clock
        "clock" to "com.sec.android.app.clockpackage",
        "ঘড়ি" to "com.sec.android.app.clockpackage",
        "alarm" to "com.sec.android.app.clockpackage",
        "অ্যালার্ম" to "com.sec.android.app.clockpackage",
        // Facebook Messenger
        "messenger" to "com.facebook.orca",
        "মেসেঞ্জার" to "com.facebook.orca",
        // Twitter/X
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "টুইটার" to "com.twitter.android",
        // TikTok
        "tiktok" to "com.zhiliaoapp.musically",
        "টিকটক" to "com.zhiliaoapp.musically",
        // Spotify
        "spotify" to "com.spotify.music",
        "স্পটিফাই" to "com.spotify.music",
        // Samsung Phone/Dialer
        "phone" to "com.samsung.android.dialer",
        "ফোন" to "com.samsung.android.dialer",
        "dialer" to "com.samsung.android.dialer",
        "ডায়ালার" to "com.samsung.android.dialer",
        "কল" to "com.samsung.android.dialer",
        // Gmail
        "gmail" to "com.google.android.gm",
        "জিমেইল" to "com.google.android.gm",
        // Play Store
        "play store" to "com.android.vending",
        "প্লে স্টোর" to "com.android.vending",
        // Samsung My Files
        "file manager" to "com.sec.android.app.myfiles",
        "files" to "com.sec.android.app.myfiles",
        "ফাইল" to "com.sec.android.app.myfiles",
        "my files" to "com.sec.android.app.myfiles",
        "মাই ফাইলস" to "com.sec.android.app.myfiles",
        // Samsung Internet
        "samsung internet" to "com.sec.android.app.sbrowser",
        "samsung browser" to "com.sec.android.app.sbrowser",
        "স্যামসাং ইন্টারনেট" to "com.sec.android.app.sbrowser",
        // Samsung Messages
        "messages" to "com.samsung.android.messaging",
        "মেসেজ" to "com.samsung.android.messaging",
        "sms" to "com.samsung.android.messaging",
        "এসএমএস" to "com.samsung.android.messaging",
        // Samsung Contacts
        "contacts" to "com.samsung.android.contacts",
        "কন্টাক্টস" to "com.samsung.android.contacts",
        // Samsung Calendar
        "calendar" to "com.samsung.android.calendar",
        "ক্যালেন্ডার" to "com.samsung.android.calendar",
        // Samsung Notes
        "notes" to "com.samsung.android.app.notes",
        "নোটস" to "com.samsung.android.app.notes",
        "samsung notes" to "com.samsung.android.app.notes",
        // Samsung Email
        "email" to "com.samsung.android.email.provider",
        "ইমেইল" to "com.samsung.android.email.provider",
        // Google app
        "google" to "com.google.android.googlequicksearchbox",
        "গুগল" to "com.google.android.googlequicksearchbox",
        // Netflix
        "netflix" to "com.netflix.mediaclient",
        "নেটফ্লিক্স" to "com.netflix.mediaclient",
        // Snapchat
        "snapchat" to "com.snapchat.android",
        "স্ন্যাপচ্যাট" to "com.snapchat.android",
        // LinkedIn
        "linkedin" to "com.linkedin.android",
        "লিংকডইন" to "com.linkedin.android",
        // Pinterest
        "pinterest" to "com.pinterest",
        // Amazon
        "amazon" to "com.amazon.mShop.android.shopping",
        "আমাজন" to "com.amazon.mShop.android.shopping",
        // Daraz
        "daraz" to "com.daraz.android",
        "দারাজ" to "com.daraz.android",
        // bKash
        "bkash" to "com.bKash.customerapp",
        "বিকাশ" to "com.bKash.customerapp",
        // Nagad
        "nagad" to "com.konasl.nagad",
        "নগদ" to "com.konasl.nagad",
        // Imo
        "imo" to "com.imo.android.imoim",
        "ইমো" to "com.imo.android.imoim",
        // Viber
        "viber" to "com.viber.voip",
        "ভাইবার" to "com.viber.voip",
        // Zoom
        "zoom" to "us.zoom.videomeetings",
        "জুম" to "us.zoom.videomeetings",
    )

    // Fallback package lists (Samsung → generic)
    private val fallbackPackages = mapOf(
        "com.sec.android.app.camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
        "com.sec.android.app.popupcalculator" to listOf("com.google.android.calculator", "com.android.calculator2"),
        "com.sec.android.gallery3d" to listOf("com.google.android.apps.photos"),
        "com.sec.android.app.clockpackage" to listOf("com.google.android.deskclock"),
        "com.samsung.android.dialer" to listOf("com.android.dialer", "com.google.android.dialer"),
        "com.sec.android.app.myfiles" to listOf("com.google.android.documentsui"),
        "com.samsung.android.messaging" to listOf("com.google.android.apps.messaging", "com.android.mms"),
        "com.samsung.android.contacts" to listOf("com.google.android.contacts", "com.android.contacts"),
        "com.samsung.android.calendar" to listOf("com.google.android.calendar"),
    )

    suspend fun acknowledge(text: String) {
        reply(text)
    }

    suspend fun process(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return
        val lower = input.lowercase()

        saveMessage("user", input)

        try {
            when {
                isStopCommand(lower) -> {
                    reply("ঠিক আছে Boss, আমি নীরব মোডে যাচ্ছি।")
                    JarvisForegroundService.instance?.stopSpeechRecognition()
                }

                isJustWakeWord(lower) -> {
                    reply("জি Boss, আমি আছি। বলুন কী করব?")
                }

                isPresenceCheck(lower) -> {
                    reply("জি Boss, আমি এখানে আছি। বলুন কী করতে হবে?")
                }

                isAppOpenCommand(lower) -> {
                    handleAppOpen(input, lower)
                }

                isScreenReadCommand(lower) -> {
                    handleScreenRead(lower)
                }

                isVolumeCommand(lower) -> {
                    handleVolume(lower)
                }

                isContactSaveCommand(lower) -> {
                    handleContactSave(input, lower)
                }

                isReminderCommand(lower) -> {
                    handleReminder(input, lower)
                }

                isReminderAck(lower) -> {
                    handleReminderAck()
                }

                isNavCommand(lower) -> {
                    handleNavigation(lower)
                }

                isSendCommand(lower) -> {
                    handleSend(input, lower)
                }

                isSearchCommand(lower) -> {
                    handleSearch(input, lower)
                }

                isGoToCommand(lower) -> {
                    handleGoTo(input, lower)
                }

                isTypeCommand(lower) -> {
                    handleTypeText(input, lower)
                }

                isClickCommand(lower) -> {
                    handleClick(input, lower)
                }

                isLockCommand(lower) -> {
                    reply("Boss, ফোনের স্ক্রিন লক করে দিচ্ছি।")
                    JarvisAccessibilityService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    )
                }

                else -> {
                    handleAiQuestion(input)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command", e)
            reply("Boss, একটু সমস্যা হচ্ছে। আবার চেষ্টা করুন।")
        }
    }

    // --- Intent Detection ---

    private fun isStopCommand(lower: String): Boolean {
        return lower.contains("অফ হয়ে যাও") || lower.contains("ঘুমিয়ে পড়ো") ||
                lower.contains("stop listening") || lower.contains("চুপ থাকো") ||
                lower.contains("বন্ধ হও") || lower.contains("নীরব") ||
                lower.contains("সাইলেন্ট মোড") || lower.contains("silent mode") ||
                lower.contains("go to sleep") || lower.contains("ঘুমাও") ||
                lower.contains("থামো") || lower.contains("বিশ্রাম") ||
                lower.contains("যাও এখন") || lower.contains("বন্ধ করো") ||
                lower.contains("stop jarvis") || lower.contains("জার্ভিস বন্ধ") ||
                lower.contains("জার্ভিস থামো")
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

    private fun isSendCommand(lower: String): Boolean {
        return lower.contains("সেন্ড কর") || lower.contains("পাঠাও") ||
                lower.contains("পাঠিয়ে দাও") || lower.contains("send it") ||
                lower.contains("send koro") || lower.contains("পাঠাও") ||
                lower == "সেন্ড" || lower == "send" || lower.contains("সেন্ড করো") ||
                lower.contains("পাঠানো")
    }

    private fun isSearchCommand(lower: String): Boolean {
        return lower.contains("সার্চ কর") || lower.contains("সার্চ করো") ||
                lower.contains("খুঁজো") || lower.contains("খোঁজো") ||
                lower.contains("search for") || lower.contains("search ") ||
                lower.contains("ফাইন্ড কর")
    }

    private fun isGoToCommand(lower: String): Boolean {
        return lower.contains("ঢুকো") || lower.contains("ঢোকো") ||
                lower.contains("যাও") || lower.contains("go to ") ||
                lower.contains("এ যাও") || lower.contains("তে যাও") ||
                lower.contains("enter ") || lower.contains("ঢুকে") ||
                lower.contains("ওপেনে") || lower.contains("তে ঢুকো") ||
                lower.contains("এ ঢুকো")
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
            val success = tryOpenPackage(packageName)
            if (success) {
                reply("$appName ওপেন করেছি Boss। এখন কী করব?")
            } else {
                reply("Boss, $appName ওপেন করতে পারলাম না। অ্যাপটি ইনস্টল আছে কিনা দেখুন।")
            }
        } else {
            reply("Boss, $appName নামের অ্যাপটি চিনতে পারছি না। পুরো নামটা বলুন।")
        }
    }

    private fun tryOpenPackage(packageName: String): Boolean {
        val service = JarvisAccessibilityService.instance
        if (service != null && service.openApp(packageName)) return true

        // Try fallback packages
        fallbackPackages[packageName]?.forEach { fallback ->
            if (service?.openApp(fallback) == true) return true
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(fallback)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }

        // Last resort: direct intent
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (_: Exception) { false }
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
        appPackages[lower]?.let { return it }
        for ((key, pkg) in appPackages) {
            if (lower.contains(key) || key.contains(lower)) {
                return pkg
            }
        }
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
        } catch (_: Exception) { null }
    }

    private suspend fun handleGoTo(input: String, lower: String) {
        val target = extractGoToTarget(input, lower)
        if (target.isBlank()) {
            reply("Boss, কোথায় যাব বলুন।")
            return
        }

        updateOverlay("\"$target\" খুঁজছি...")
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            reply("Boss, Accessibility Service চালু নেই। সেটিংস থেকে চালু করুন।")
            return
        }

        val found = withContext(Dispatchers.IO) {
            service.searchAndClick(target)
        }
        if (found) {
            reply("\"$target\" এ গেছি Boss। এখন কী করব?")
        } else {
            // Try via search field
            val searched = trySearchInCurrentApp(service, target)
            if (searched) {
                reply("\"$target\" সার্চ করে পেয়েছি Boss।")
            } else {
                reply("Boss, \"$target\" খুঁজে পাচ্ছি না স্ক্রিনে। নামটা চেক করুন।")
            }
        }
    }

    private fun extractGoToTarget(input: String, lower: String): String {
        val patterns = listOf(
            "ঢুকো ", "ঢোকো ", "যাও ", "go to ", "enter ",
            "এ ঢুকো", "তে ঢুকো", "এ যাও", "তে যাও",
            "ঢুকো", "ঢোকো", "যাও", "ঢুকে",
        )
        var cleaned = input
        for (p in patterns) {
            cleaned = cleaned.replace(p, " ", ignoreCase = true)
        }
        return cleaned.trim()
    }

    private suspend fun handleSearch(input: String, lower: String) {
        val query = extractSearchQuery(input, lower)
        if (query.isBlank()) {
            reply("Boss, কী সার্চ করব বলুন।")
            return
        }

        val service = JarvisAccessibilityService.instance
        if (service == null) {
            reply("Boss, Accessibility Service চালু নেই।")
            return
        }

        updateOverlay("\"$query\" সার্চ করছি...")

        val success = trySearchInCurrentApp(service, query)
        if (success) {
            reply("\"$query\" সার্চ করেছি Boss।")
        } else {
            reply("Boss, সার্চ ফিল্ড পাচ্ছি না এই অ্যাপে।")
        }
    }

    private suspend fun trySearchInCurrentApp(service: JarvisAccessibilityService, query: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (service.clickSearchField()) {
                Thread.sleep(500)
                if (service.performTypeText(query)) {
                    Thread.sleep(800)
                    if (service.performClickOnText(query)) {
                        return@withContext true
                    }
                    return@withContext true
                }
            }
            false
        }
    }

    private fun extractSearchQuery(input: String, lower: String): String {
        val patterns = listOf(
            "সার্চ করো ", "সার্চ কর ", "খুঁজো ", "খোঁজো ",
            "search for ", "search ", "ফাইন্ড করো ", "ফাইন্ড কর "
        )
        var cleaned = input
        for (p in patterns) {
            cleaned = cleaned.replace(p, "", ignoreCase = true)
        }
        return cleaned.trim()
    }

    private suspend fun handleSend(input: String, lower: String) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            reply("Boss, Accessibility Service চালু নেই।")
            return
        }

        val success = withContext(Dispatchers.IO) {
            service.clickSendButton()
        }
        if (success) {
            reply("সেন্ড করে দিয়েছি Boss।")
        } else {
            reply("Boss, সেন্ড বাটন পাচ্ছি না।")
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
                Log.e(TAG, "Translation error", e)
                null
            }
        }
    }

    private suspend fun handleVolume(lower: String) {
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
        val numberRegex = Regex("[+]?[0-9\\s\\-]{7,15}")
        var number = numberRegex.find(input)?.value?.replace(Regex("[\\s\\-]"), "")

        if (number == null) {
            val screenText = JarvisAccessibilityService.currentScreenText
            number = numberRegex.find(screenText)?.value?.replace(Regex("[\\s\\-]"), "")
        }

        if (number == null) {
            reply("Boss, কোনো নাম্বার পাচ্ছি না। নাম্বারটা বলুন।")
            return
        }

        reply("Boss, নাম্বার পেয়েছি: $number। কন্টাক্টের নাম কী দেব?")
        pendingContactNumber = number
    }

    private suspend fun handleReminder(input: String, lower: String) {
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
            withContext(Dispatchers.IO) { db.reminderDao().insert(reminder) }
            reminderManager.scheduleReminder(reminder)
            val timeDesc = formatDuration(durationMs)
            reply("Done Boss! $timeDesc পরে মনে করিয়ে দেব: \"$reminderText\"")
        } else if (reminderText.isNotEmpty()) {
            val triggerTime = System.currentTimeMillis() + 3600_000L
            val reminder = Reminder(
                text = reminderText,
                triggerTimeMillis = triggerTime,
                isAcknowledged = false,
                createdAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { db.reminderDao().insert(reminder) }
            reminderManager.scheduleReminder(reminder)
            reply("Boss, ১ ঘণ্টা পরে মনে করিয়ে দেব: \"$reminderText\"। সময় ঠিক না হলে বলুন।")
        } else {
            reply("Boss, কী মনে করাইতে চান আর কখন — দুটোই বলুন।")
        }
    }

    private fun parseTimeDuration(lower: String): Long {
        var totalMs = 0L
        val minRegex = Regex("(\\d+)\\s*(মিনিট|minute|min)")
        minRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(60_000L) ?: 0
        }
        val hourRegex = Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hour|hr)")
        hourRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(3600_000L) ?: 0
        }
        val dayRegex = Regex("(\\d+)\\s*(দিন|day)")
        dayRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(86_400_000L) ?: 0
        }
        val weekRegex = Regex("(\\d+)\\s*(সপ্তাহ|week)")
        weekRegex.find(lower)?.let {
            totalMs += it.groupValues[1].toLongOrNull()?.times(604_800_000L) ?: 0
        }
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
            val triggered = active.filter { it.triggerTimeMillis <= System.currentTimeMillis() }
            for (r in triggered) {
                db.reminderDao().markAcknowledged(r.id)
                db.reminderDao().deleteAcknowledged()
            }
        }
        reply("ঠিক আছে Boss, রিমাইন্ডার মুছে দিয়েছি।")
    }

    private suspend fun handleNavigation(lower: String) {
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

    private suspend fun handleTypeText(input: String, lower: String) {
        val patterns = listOf("লিখো ", "লেখো ", "টাইপ করো ", "টাইপ কর ", "type ")
        var textToType = input
        for (p in patterns) {
            textToType = textToType.replace(p, "", ignoreCase = true)
        }
        textToType = textToType.trim().removeSurrounding("\"").removeSurrounding("'")

        if (textToType.isNotEmpty()) {
            val service = JarvisAccessibilityService.instance
            val success = service?.tapEditFieldAndType(textToType) ?: false
            if (success) {
                reply("লিখে দিয়েছি Boss।")
            } else {
                val retrySuccess = service?.performTypeText(textToType) ?: false
                if (retrySuccess) {
                    reply("লিখে দিয়েছি Boss।")
                } else {
                    reply("Boss, টেক্সট ফিল্ড পাচ্ছি না। আগে যেখানে লিখতে চান সেখানে ট্যাপ করুন।")
                }
            }
        } else {
            reply("Boss, কী লিখব বলুন।")
        }
    }

    private suspend fun handleClick(input: String, lower: String) {
        val patterns = listOf("ক্লিক করো ", "ক্লিক কর ", "ট্যাপ করো ", "ট্যাপ কর ", "চাপো ", "press ", "click ", "tap ")
        var target = input
        for (p in patterns) {
            target = target.replace(p, "", ignoreCase = true)
        }
        target = target.trim().removeSurrounding("\"").removeSurrounding("'")

        if (target.isNotEmpty()) {
            val success = JarvisAccessibilityService.instance?.searchAndClick(target) ?: false
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
        updateOverlay("Boss এর জন্য উত্তর আনছি...")

        aiBrain.provider = settings.getAiProvider()
        aiBrain.apiKey = settings.getAiApiKey()
        val screenContext = JarvisAccessibilityService.currentScreenText
        val response = aiBrain.ask(input, screenContext, settings.getLanguage())

        reply(response)
    }

    private suspend fun reply(text: String) {
        saveMessage("assistant", text)
        JarvisForegroundService.lastReplyText = text
        JarvisForegroundService.replyListeners.forEach { it(text) }
        updateOverlay(text.take(60) + if (text.length > 60) "..." else "")

        try {
            voiceEngine.speak(text)
        } catch (e: Exception) {
            Log.e(TAG, "Voice engine error", e)
        }
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
        private const val TAG = "CommandProcessor"
        var pendingContactNumber: String? = null
    }
}

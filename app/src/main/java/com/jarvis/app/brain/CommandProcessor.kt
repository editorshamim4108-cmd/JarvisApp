package com.jarvis.app.brain

import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import com.jarvis.app.data.AppDatabase
import com.jarvis.app.data.ConversationMessage
import com.jarvis.app.data.Reminder
import com.jarvis.app.data.ReminderManager
import com.jarvis.app.receiver.PhoneCallReceiver
import com.jarvis.app.service.JarvisAccessibilityService
import com.jarvis.app.service.JarvisForegroundService
import com.jarvis.app.service.JarvisNotificationListener
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
        "whatsapp" to "com.whatsapp", "whats app" to "com.whatsapp",
        "watts app" to "com.whatsapp", "whatapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp", "হোয়াটস অ্যাপ" to "com.whatsapp",
        "ওয়াটসঅ্যাপ" to "com.whatsapp", "ওয়াটস অ্যাপ" to "com.whatsapp",
        "youtube" to "com.google.android.youtube", "ইউটিউব" to "com.google.android.youtube",
        "facebook" to "com.facebook.katana", "ফেসবুক" to "com.facebook.katana",
        "fb" to "com.facebook.katana",
        "instagram" to "com.instagram.android", "ইনস্টাগ্রাম" to "com.instagram.android",
        "ইন্সটা" to "com.instagram.android",
        "chrome" to "com.android.chrome", "ক্রোম" to "com.android.chrome",
        "camera" to "com.sec.android.app.camera", "ক্যামেরা" to "com.sec.android.app.camera",
        "settings" to "com.android.settings", "সেটিংস" to "com.android.settings",
        "সেটিং" to "com.android.settings",
        "telegram" to "org.telegram.messenger", "টেলিগ্রাম" to "org.telegram.messenger",
        "maps" to "com.google.android.apps.maps", "ম্যাপ" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps", "গুগল ম্যাপ" to "com.google.android.apps.maps",
        "calculator" to "com.sec.android.app.popupcalculator", "ক্যালকুলেটর" to "com.sec.android.app.popupcalculator",
        "gallery" to "com.sec.android.gallery3d", "গ্যালারি" to "com.sec.android.gallery3d",
        "photos" to "com.google.android.apps.photos", "ফটোস" to "com.google.android.apps.photos",
        "clock" to "com.sec.android.app.clockpackage", "ঘড়ি" to "com.sec.android.app.clockpackage",
        "alarm" to "com.sec.android.app.clockpackage", "অ্যালার্ম" to "com.sec.android.app.clockpackage",
        "messenger" to "com.facebook.orca", "মেসেঞ্জার" to "com.facebook.orca",
        "twitter" to "com.twitter.android", "x" to "com.twitter.android",
        "tiktok" to "com.zhiliaoapp.musically", "টিকটক" to "com.zhiliaoapp.musically",
        "spotify" to "com.spotify.music", "স্পটিফাই" to "com.spotify.music",
        "phone" to "com.samsung.android.dialer", "ফোন" to "com.samsung.android.dialer",
        "dialer" to "com.samsung.android.dialer", "কল" to "com.samsung.android.dialer",
        "gmail" to "com.google.android.gm", "জিমেইল" to "com.google.android.gm",
        "play store" to "com.android.vending", "প্লে স্টোর" to "com.android.vending",
        "file manager" to "com.sec.android.app.myfiles", "files" to "com.sec.android.app.myfiles",
        "ফাইল" to "com.sec.android.app.myfiles", "my files" to "com.sec.android.app.myfiles",
        "samsung internet" to "com.sec.android.app.sbrowser",
        "messages" to "com.samsung.android.messaging", "মেসেজ" to "com.samsung.android.messaging",
        "sms" to "com.samsung.android.messaging",
        "contacts" to "com.samsung.android.contacts", "কন্টাক্টস" to "com.samsung.android.contacts",
        "calendar" to "com.samsung.android.calendar", "ক্যালেন্ডার" to "com.samsung.android.calendar",
        "notes" to "com.samsung.android.app.notes", "নোটস" to "com.samsung.android.app.notes",
        "google" to "com.google.android.googlequicksearchbox", "গুগল" to "com.google.android.googlequicksearchbox",
        "netflix" to "com.netflix.mediaclient", "নেটফ্লিক্স" to "com.netflix.mediaclient",
        "bkash" to "com.bKash.customerapp", "বিকাশ" to "com.bKash.customerapp",
        "nagad" to "com.konasl.nagad", "নগদ" to "com.konasl.nagad",
        "imo" to "com.imo.android.imoim", "ইমো" to "com.imo.android.imoim",
        "viber" to "com.viber.voip", "ভাইবার" to "com.viber.voip",
        "zoom" to "us.zoom.videomeetings", "জুম" to "us.zoom.videomeetings",
        "daraz" to "com.daraz.android", "দারাজ" to "com.daraz.android",
    )

    private val fallbackPackages = mapOf(
        "com.sec.android.app.camera" to listOf("com.android.camera", "com.android.camera2"),
        "com.sec.android.app.popupcalculator" to listOf("com.google.android.calculator"),
        "com.sec.android.gallery3d" to listOf("com.google.android.apps.photos"),
        "com.sec.android.app.clockpackage" to listOf("com.google.android.deskclock"),
        "com.samsung.android.dialer" to listOf("com.android.dialer", "com.google.android.dialer"),
        "com.sec.android.app.myfiles" to listOf("com.google.android.documentsui"),
        "com.samsung.android.messaging" to listOf("com.google.android.apps.messaging"),
        "com.samsung.android.contacts" to listOf("com.google.android.contacts"),
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
                    reply("ঠিক আছে Boss, নীরব মোডে যাচ্ছি।")
                    JarvisForegroundService.instance?.stopSpeechRecognition()
                }
                isJustWakeWord(lower) -> reply("জি Boss, আমি আছি। বলুন কী করব?")
                isPresenceCheck(lower) -> reply("জি Boss, আমি এখানে আছি।")

                // ★ CALL: answer or reject incoming call
                isCallAnswerCommand(lower) -> handleAnswerCall()
                isCallRejectCommand(lower) -> handleRejectCall()

                // ★ READ NOTIFICATION: "মেসেজ পড়ো", "কী লিখেছে"
                isReadNotificationCommand(lower) -> handleReadLastNotification()

                // ★ COMPOUND: "YouTube ওপেন করে গান প্লে করো" or "WhatsApp খুলে মেসেজ পাঠাও"
                isCompoundCommand(lower) -> handleCompoundCommand(input, lower)

                // ★ MESSAGING: "SK Bazar কে WhatsApp এ Hello পাঠাও"
                isMessageCommand(lower) -> handleMessageCommand(input, lower)

                // ★ PLAY: "Arijit Singh এর গান প্লে করো"
                isPlayCommand(lower) -> handlePlayCommand(input, lower)

                // Simple app open
                isAppOpenCommand(lower) -> handleAppOpen(input, lower)

                // In-app actions (when app is already open)
                isGoToCommand(lower) -> handleGoTo(input, lower)
                isSendCommand(lower) -> handleSend()
                isSearchCommand(lower) -> handleSearch(input, lower)
                isTypeCommand(lower) -> handleTypeText(input, lower)
                isClickCommand(lower) -> handleClick(input, lower)

                // Utility
                isScreenReadCommand(lower) -> handleScreenRead(lower)
                isVolumeCommand(lower) -> handleVolume(lower)
                isNavCommand(lower) -> handleNavigation(lower)
                isReminderCommand(lower) -> handleReminder(input, lower)
                isReminderAck(lower) -> handleReminderAck()
                isContactSaveCommand(lower) -> handleContactSave(input, lower)
                isLockCommand(lower) -> {
                    reply("Boss, স্ক্রিন লক করছি।")
                    JarvisAccessibilityService.instance?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                    )
                }

                // AI fallback
                else -> handleAiQuestion(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            reply("Boss, সমস্যা হচ্ছে। আবার বলুন।")
        }
    }

    // ===================== INTENT DETECTION =====================

    private fun isStopCommand(l: String) = l.contains("সাইলেন্ট মোড") || l.contains("silent mode") ||
            l.contains("থামো") || l.contains("বন্ধ করো") || l.contains("stop") ||
            l.contains("ঘুমাও") || l.contains("চুপ থাকো") || l.contains("নীরব") ||
            l.contains("অফ হয়ে যাও") || l.contains("বিশ্রাম") || l.contains("ঘুমিয়ে পড়ো")

    private fun isJustWakeWord(l: String) =
        l in listOf("hey jarvis", "হেই জার্ভিস", "জার্ভিস", "jarvis", "হে জার্ভিস")

    private fun isPresenceCheck(l: String) =
        l.contains("তুমি আছো") || l.contains("তুমি কি আছো") || l == "আছো?" || l.contains("are you there")

    private fun isCompoundCommand(l: String): Boolean {
        val hasSplit = l.contains("ওপেন করে") || l.contains("খুলে") || l.contains("চালু করে") ||
                l.contains("open ") && (l.contains(" and ") || l.contains(" then "))
        val hasAction = l.contains("প্লে") || l.contains("play") || l.contains("সার্চ") ||
                l.contains("search") || l.contains("পাঠাও") || l.contains("send") ||
                l.contains("লিখো") || l.contains("ঢুকো") || l.contains("গান") ||
                l.contains("ভিডিও") || l.contains("video") || l.contains("মেসেজ") ||
                l.contains("খুঁজো")
        return hasSplit && hasAction
    }

    private fun isMessageCommand(l: String): Boolean {
        val hasMsg = l.contains("মেসেজ") || l.contains("message") || l.contains("পাঠাও") ||
                l.contains("পাঠিয়ে দাও") || l.contains("send")
        val hasTarget = l.contains("কে ") || l.contains(" to ")
        return hasMsg && hasTarget
    }

    private fun isPlayCommand(l: String): Boolean {
        return (l.contains("প্লে কর") || l.contains("play ") || l.contains("বাজাও") ||
                l.contains("চালাও") || l.contains("গান") || l.contains("song") ||
                l.contains("ভিডিও") || l.contains("video")) &&
                !isAppOpenCommand(l)
    }

    private fun isAppOpenCommand(l: String) =
        (l.contains("ওপেন কর") || l.contains("খোলো") || l.contains("খুলো") ||
                l.startsWith("open ") || l.contains("চালু কর") || l.contains("চালাও")) &&
                !isCompoundCommand(l)

    private fun isGoToCommand(l: String) =
        l.contains("ঢুকো") || l.contains("ঢোকো") || l.contains("এ যাও") ||
                l.contains("তে যাও") || l.contains("go to ") || l.contains("enter ")

    private fun isSendCommand(l: String) =
        (l == "সেন্ড" || l == "send" || l.contains("সেন্ড করো") || l.contains("সেন্ড কর") ||
                l.contains("পাঠিয়ে দাও") || l == "send it") && !isMessageCommand(l)

    private fun isSearchCommand(l: String) =
        (l.contains("সার্চ কর") || l.contains("সার্চ করো") || l.contains("খুঁজো") ||
                l.contains("search ")) && !isCompoundCommand(l)

    private fun isTypeCommand(l: String) =
        l.contains("লিখো") || l.contains("লেখো") || l.contains("type ") || l.contains("টাইপ কর")

    private fun isClickCommand(l: String) =
        l.contains("ক্লিক") || l.contains("click") || l.contains("ট্যাপ") || l.contains("tap") ||
                l.contains("চাপো") || l.contains("press ")

    private fun isScreenReadCommand(l: String): Boolean {
        val hasScreen = l.contains("স্ক্রিন") || l.contains("screen") || l.contains("লেখা")
        val hasAction = l.contains("পড়") || l.contains("read") || l.contains("বল") ||
                l.contains("অনুবাদ") || l.contains("translate") || l.contains("বাংলায়")
        return hasScreen && hasAction
    }

    private fun isVolumeCommand(l: String) =
        l.contains("volume") || l.contains("ভলিউম") || l.contains("সাউন্ড") || l.contains("আওয়াজ")

    private fun isNavCommand(l: String) =
        l.contains("ব্যাক") || l.contains("back") || l.contains("হোম") || l.contains("home") ||
                l.contains("স্ক্রল") || l.contains("scroll") || l.contains("নোটিফিকেশন") ||
                l.contains("notification")

    private fun isReminderCommand(l: String) =
        l.contains("মনে কর") || l.contains("রিমাইন্ডার") || l.contains("remind") || l.contains("মনে রাখ")

    private fun isReminderAck(l: String) =
        l == "ঠিক আছে" || l == "ok" || l == "okay" || l == "done" || l == "হয়ে গেছে"

    private fun isContactSaveCommand(l: String): Boolean {
        val hasNumber = l.contains("কন্টাক্ট") || l.contains("contact") || l.contains("নাম্বার") || l.contains("নম্বর")
        val hasSave = l.contains("সেভ") || l.contains("save") || l.contains("অ্যাড") || l.contains("add") || l.contains("রাখো") || l.contains("রাখ")
        if (hasNumber && hasSave) return true
        val hasCall = l.contains("ফোন দিল") || l.contains("ফোন দিয়েছ") || l.contains("কল এসেছ") ||
                l.contains("কল দিয়েছ") || l.contains("last call") || l.contains("এইমাত্র কল")
        return hasCall && hasSave
    }

    private fun isCallAnswerCommand(l: String) =
        PhoneCallReceiver.isRinging &&
                (l.contains("ধরো") || l.contains("রিসিভ") || l.contains("answer") ||
                 l.contains("pick up") || l.contains("উঠাও") || l.contains("ধর"))

    private fun isCallRejectCommand(l: String) =
        PhoneCallReceiver.isRinging &&
                (l.contains("কাটো") || l.contains("কেটে দাও") || l.contains("reject") ||
                 l.contains("decline") || l.contains("রিজেক্ট") || l.contains("কাট"))

    private fun isReadNotificationCommand(l: String) =
        l.contains("মেসেজ পড়ো") || l.contains("কী লিখেছে") || l.contains("read message") ||
                l.contains("কী বলেছে") || l.contains("মেসেজটা কী") || l.contains("কে মেসেজ দিয়েছে") ||
                l.contains("নোটিফিকেশন পড়ো") || l.contains("last message")

    private fun isLockCommand(l: String) =
        l.contains("লক কর") || l.contains("lock") || l.contains("ফোন লক")

    // ===================== COMPOUND COMMAND =====================

    private suspend fun handleCompoundCommand(input: String, lower: String) {
        val splitPatterns = listOf("ওপেন করে ", "খুলে ", "চালু করে ", " and then ", " then ")
        var appPart = ""
        var actionPart = ""

        for (p in splitPatterns) {
            val idx = lower.indexOf(p)
            if (idx >= 0) {
                appPart = input.substring(0, idx).trim()
                actionPart = input.substring(idx + p.length).trim()
                break
            }
        }

        if (appPart.isEmpty() || actionPart.isEmpty()) {
            handleAiQuestion(input)
            return
        }

        val cleanAppName = extractAppNameFromPart(appPart)
        val pkgName = findPackageName(cleanAppName)

        if (pkgName == null) {
            reply("Boss, $cleanAppName অ্যাপটি পাচ্ছি না।")
            return
        }

        updateOverlay("$cleanAppName খুলছি...")
        if (!tryOpenPackage(pkgName)) {
            reply("Boss, $cleanAppName খুলতে পারলাম না।")
            return
        }

        delay(3000)

        val actionLower = actionPart.lowercase()
        when {
            actionLower.contains("গান") || actionLower.contains("song") ||
            actionLower.contains("প্লে") || actionLower.contains("play") ||
            actionLower.contains("ভিডিও") || actionLower.contains("video") ||
            actionLower.contains("বাজাও") || actionLower.contains("চালাও") -> {
                val query = extractPlayQuery(actionPart)
                if (query.isNotEmpty()) {
                    updateOverlay("\"$query\" সার্চ করছি...")
                    performAppSearch(query)
                    delay(2000)
                    updateOverlay("\"$query\" প্লে করছি...")
                    val svc = JarvisAccessibilityService.instance
                    if (svc?.performClickOnText(query) == true || svc?.clickFirstClickableItem() == true) {
                        reply("Boss, টাস্ক কমপ্লিট! $cleanAppName খুলেছি → \"$query\" সার্চ করেছি → প্লে করছি!")
                    } else {
                        reply("Boss, $cleanAppName খুলেছি → \"$query\" সার্চ করেছি। যেটা চান ট্যাপ করুন।")
                    }
                } else {
                    updateOverlay("প্রথম ভিডিও প্লে করছি...")
                    delay(1000)
                    val svc = JarvisAccessibilityService.instance
                    if (svc?.clickFirstClickableItem() == true) {
                        reply("Boss, $cleanAppName খুলেছি → প্রথম ভিডিও প্লে করছি!")
                    } else {
                        reply("Boss, $cleanAppName খুলেছি। কী প্লে করব বলুন!")
                    }
                }
            }
            actionLower.contains("সার্চ") || actionLower.contains("search") ||
            actionLower.contains("খুঁজো") -> {
                val query = cleanActionText(actionPart, listOf("সার্চ করো", "সার্চ কর", "খুঁজো", "search for", "search"))
                if (query.isNotEmpty()) {
                    performAppSearch(query)
                    reply("Boss, $query সার্চ করেছি।")
                } else {
                    reply("Boss, $cleanAppName খুলেছি। কী সার্চ করব?")
                }
            }
            actionLower.contains("মেসেজ") || actionLower.contains("message") ||
            actionLower.contains("পাঠাও") || actionLower.contains("send") -> {
                handleInAppMessaging(actionPart, actionLower)
            }
            actionLower.contains("ঢুকো") || actionLower.contains("যাও") -> {
                val target = cleanActionText(actionPart, listOf("ঢুকো", "এ ঢুকো", "তে ঢুকো", "যাও", "এ যাও"))
                if (target.isNotEmpty()) {
                    val svc = JarvisAccessibilityService.instance
                    if (svc?.searchAndClick(target) == true) {
                        reply("Boss, $target এ গেছি!")
                    } else {
                        performAppSearch(target)
                        delay(1500)
                        if (svc?.performClickOnText(target) == true) {
                            reply("Boss, $target পেয়েছি!")
                        } else {
                            reply("Boss, $target খুঁজে পাচ্ছি না।")
                        }
                    }
                } else {
                    reply("Boss, $cleanAppName খুলেছি। কোথায় যাব?")
                }
            }
            else -> {
                reply("Boss, $cleanAppName খুলেছি। $actionPart — এটা করতে চেষ্টা করছি...")
                val svc = JarvisAccessibilityService.instance
                val words = actionPart.split(" ").filter { it.length > 2 }
                var done = false
                for (word in words) {
                    if (svc?.performClickOnText(word) == true) {
                        reply("Boss, \"$word\" ক্লিক করেছি!")
                        done = true
                        break
                    }
                }
                if (!done) reply("Boss, $cleanAppName খুলেছি। এখন কী করব বলুন!")
            }
        }
    }

    private fun extractAppNameFromPart(part: String): String {
        val prefixes = listOf("ওপেন করো", "ওপেন কর", "খোলো", "খুলো", "চালু কর", "চালাও", "open")
        var cleaned = part
        for (p in prefixes) {
            cleaned = cleaned.replace(p, "", ignoreCase = true)
        }
        return cleaned.trim().ifEmpty { part }
    }

    // ===================== PLAY COMMAND =====================

    private suspend fun handlePlayCommand(input: String, lower: String) {
        val query = extractPlayQuery(input)
        val wantsFirst = lower.contains("প্রথম") || lower.contains("first") || lower.contains("১ম")
        val currentPkg = JarvisAccessibilityService.currentPackageName

        if (currentPkg == "com.google.android.youtube" || currentPkg == "com.spotify.music") {
            val svc = JarvisAccessibilityService.instance
            if (wantsFirst) {
                if (svc?.clickFirstClickableItem() == true) {
                    reply("Boss, প্রথম ভিডিও প্লে করছি!")
                } else {
                    reply("Boss, প্লে করতে পারলাম না। স্ক্রিনে ভিডিও নেই।")
                }
            } else if (query.isNotEmpty()) {
                performAppSearch(query)
                delay(2000)
                svc?.performClickOnText(query) ?: svc?.clickFirstClickableItem()
                reply("Boss, $query প্লে করছি!")
            } else {
                if (svc?.clickFirstClickableItem() == true) {
                    reply("Boss, প্রথম ভিডিও প্লে করছি!")
                } else {
                    reply("Boss, কী প্লে করব? গানের নাম বলুন।")
                }
            }
            return
        }

        updateOverlay("YouTube খুলছি...")
        tryOpenPackage("com.google.android.youtube")
        delay(3000)

        val svc = JarvisAccessibilityService.instance
        if (wantsFirst || query.isEmpty()) {
            delay(1000)
            if (svc?.clickFirstClickableItem() == true) {
                reply("Boss, YouTube খুলে প্রথম ভিডিও প্লে করছি!")
            } else {
                reply("Boss, YouTube খুলেছি। কী প্লে করব বলুন!")
            }
        } else {
            performAppSearch(query)
            delay(2000)
            svc?.performClickOnText(query) ?: svc?.clickFirstClickableItem()
            reply("Boss, YouTube এ $query প্লে করছি!")
        }
    }

    private fun extractPlayQuery(input: String): String {
        val removePatterns = listOf(
            "প্লে করো", "প্লে কর", "চালাও", "বাজাও", "play",
            "একটা গান", "একটু গান", "গান", "song", "ভিডিও", "video",
            "youtube এ", "ইউটিউবে", "youtube", "ইউটিউব",
            "spotify এ", "স্পটিফাইয়ে", "spotify",
            "ওপেন করে", "খুলে", "চালু করে",
        )
        var cleaned = input
        for (p in removePatterns) {
            cleaned = cleaned.replace(p, " ", ignoreCase = true)
        }
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    // ===================== MESSAGE COMMAND =====================

    private suspend fun handleMessageCommand(input: String, lower: String) {
        val parsed = parseMessageCommand(input, lower)
        val contact = parsed.first
        val message = parsed.second

        if (contact.isEmpty()) {
            reply("Boss, কাকে মেসেজ পাঠাব?")
            return
        }

        val currentPkg = JarvisAccessibilityService.currentPackageName
        val inWhatsApp = currentPkg == "com.whatsapp"

        if (!inWhatsApp) {
            updateOverlay("WhatsApp খুলছি...")
            tryOpenPackage("com.whatsapp")
            delay(4500)
        }

        updateOverlay("$contact খুঁজছি...")
        val svc = JarvisAccessibilityService.instance ?: run {
            reply("Boss, Accessibility Service চালু করুন।")
            return
        }

        updateOverlay("সার্চ বার খুলছি...")
        var searchOpened = svc.clickSearchField()
        if (!searchOpened) {
            delay(1500)
            searchOpened = svc.clickNewChatButton()
            if (searchOpened) delay(1500)
            else {
                delay(1000)
                searchOpened = svc.clickSearchField()
            }
        }
        delay(1200)
        updateOverlay("\"$contact\" টাইপ করছি...")
        svc.performTypeText(contact)
        delay(2000)

        updateOverlay("\"$contact\" খুঁজছি...")
        var found = svc.performClickOnText(contact)
        if (!found) {
            delay(1000)
            found = svc.performClickOnText(contact) || svc.clickFirstClickableItem()
        }

        if (found) {
            delay(2000)
            if (message.isNotEmpty()) {
                updateOverlay("মেসেজ লিখছি...")
                svc.tapEditFieldAndType(message)
                delay(500)
                updateOverlay("সেন্ড করছি...")
                svc.clickSendButton()
                reply("Boss, টাস্ক কমপ্লিট! $contact খুঁজেছি → চ্যাটে ঢুকেছি → \"$message\" পাঠিয়ে দিয়েছি!")
            } else {
                reply("Boss, $contact এর চ্যাটে ঢুকেছি। কী লিখব বলুন!")
            }
        } else {
            reply("Boss, \"$contact\" WhatsApp এ খুঁজে পাচ্ছি না। নামটা চেক করুন।")
        }
    }

    private fun parseMessageCommand(input: String, lower: String): Pair<String, String> {
        // Pattern: "{contact} কে {message} পাঠাও"
        val keIdx = lower.indexOf(" কে ")
        if (keIdx > 0) {
            val contact = input.substring(0, keIdx).trim()
                .replace(Regex("(?i)(whatsapp|হোয়াটসঅ্যাপ|ওয়াটসঅ্যাপ)\\s*(এ|তে)?\\s*"), "")
                .trim()
            var msgPart = input.substring(keIdx + 4).trim()
            val removeEnd = listOf("পাঠাও", "পাঠিয়ে দাও", "দাও", "send", "মেসেজ পাঠাও",
                "মেসেজ দাও", "মেসেজ করো", "বলো", "লিখো")
            for (r in removeEnd) {
                msgPart = msgPart.replace(r, "", ignoreCase = true)
            }
            return Pair(contact.ifEmpty { "" }, msgPart.trim())
        }

        // Pattern: "send {message} to {contact}"
        val toIdx = lower.indexOf(" to ")
        if (toIdx > 0) {
            var msgPart = input.substring(0, toIdx).trim()
            val contact = input.substring(toIdx + 4).trim()
                .replace(Regex("(?i)on\\s+whatsapp"), "").trim()
            msgPart = msgPart.replace(Regex("(?i)^(send|message)\\s*"), "").trim()
            return Pair(contact, msgPart)
        }

        return Pair("", "")
    }

    private suspend fun handleInAppMessaging(actionPart: String, actionLower: String) {
        val svc = JarvisAccessibilityService.instance ?: return

        val contactAndMsg = parseMessageCommand(actionPart, actionLower)
        val contact = contactAndMsg.first
        val message = contactAndMsg.second

        if (contact.isNotEmpty()) {
            updateOverlay("সার্চ বার খুলছি...")
            var searchOpened = svc.clickSearchField()
            if (!searchOpened) {
                delay(1500)
                searchOpened = svc.clickNewChatButton()
                if (searchOpened) delay(1500)
                else {
                    delay(1000)
                    svc.clickSearchField()
                }
            }
            delay(1200)
            updateOverlay("\"$contact\" টাইপ করছি...")
            svc.performTypeText(contact)
            delay(2000)
            updateOverlay("\"$contact\" খুঁজছি...")
            var found = svc.performClickOnText(contact)
            if (!found) {
                delay(1000)
                found = svc.performClickOnText(contact) || svc.clickFirstClickableItem()
            }
            if (found) {
                delay(2000)
                if (message.isNotEmpty()) {
                    updateOverlay("মেসেজ লিখছি...")
                    svc.tapEditFieldAndType(message)
                    delay(500)
                    updateOverlay("সেন্ড করছি...")
                    svc.clickSendButton()
                    reply("Boss, টাস্ক কমপ্লিট! $contact খুঁজেছি → চ্যাটে ঢুকেছি → \"$message\" পাঠিয়েছি!")
                } else {
                    reply("Boss, $contact এর চ্যাটে ঢুকেছি। কী লিখব?")
                }
            } else {
                reply("Boss, $contact খুঁজে পাচ্ছি না।")
            }
        } else {
            reply("Boss, কাকে মেসেজ পাঠাব?")
        }
    }

    // ===================== APP SEARCH (reusable) =====================

    private suspend fun performAppSearch(query: String) {
        val svc = JarvisAccessibilityService.instance ?: return
        var opened = svc.clickSearchField()
        if (!opened) {
            opened = svc.clickByContentDescription("search")
        }
        if (!opened) {
            delay(1500)
            opened = svc.clickSearchField() || svc.clickByContentDescription("search")
        }
        if (opened) {
            delay(800)
            svc.performTypeText(query)
            delay(800)
            svc.pressKeyboardAction()
            delay(1500)
        }
    }

    // ===================== SIMPLE HANDLERS =====================

    private suspend fun handleAppOpen(input: String, lower: String) {
        val appName = extractAppName(input)
        val pkg = findPackageName(appName)
        if (pkg != null && tryOpenPackage(pkg)) {
            reply("$appName ওপেন করেছি Boss। এখন কী করব?")
        } else if (pkg != null) {
            reply("Boss, $appName খুলতে পারলাম না।")
        } else {
            reply("Boss, $appName চিনতে পারছি না। পুরো নাম বলুন।")
        }
    }

    private suspend fun handleGoTo(input: String, lower: String) {
        val target = cleanActionText(input, listOf("ঢুকো", "ঢোকো", "এ ঢুকো", "তে ঢুকো", "যাও", "এ যাও", "তে যাও", "go to", "enter"))
        if (target.isBlank()) { reply("Boss, কোথায় যাব?"); return }
        updateOverlay("\"$target\" খুঁজছি...")
        val svc = JarvisAccessibilityService.instance
        if (svc?.searchAndClick(target) == true) {
            reply("\"$target\" এ গেছি Boss!")
        } else {
            performAppSearch(target)
            delay(1500)
            if (svc?.performClickOnText(target) == true) {
                reply("\"$target\" পেয়ে গেছি Boss!")
            } else {
                reply("Boss, \"$target\" পাচ্ছি না স্ক্রিনে।")
            }
        }
    }

    private suspend fun handleSend() {
        val svc = JarvisAccessibilityService.instance
        if (svc?.clickSendButton() == true) {
            reply("সেন্ড করেছি Boss!")
        } else {
            reply("Boss, সেন্ড বাটন পাচ্ছি না।")
        }
    }

    private suspend fun handleSearch(input: String, lower: String) {
        val query = cleanActionText(input, listOf("সার্চ করো", "সার্চ কর", "খুঁজো", "খোঁজো", "search for", "search"))
        if (query.isBlank()) { reply("Boss, কী সার্চ করব?"); return }
        updateOverlay("\"$query\" সার্চ করছি...")
        performAppSearch(query)
        reply("\"$query\" সার্চ করেছি Boss।")
    }

    private suspend fun handleTypeText(input: String, lower: String) {
        val text = cleanActionText(input, listOf("লিখো", "লেখো", "টাইপ করো", "টাইপ কর", "type"))
            .removeSurrounding("\"").removeSurrounding("'")
        if (text.isEmpty()) { reply("Boss, কী লিখব?"); return }
        val svc = JarvisAccessibilityService.instance
        if (svc?.tapEditFieldAndType(text) == true || svc?.performTypeText(text) == true) {
            reply("লিখেছি Boss!")
        } else {
            reply("Boss, টেক্সট ফিল্ড পাচ্ছি না। আগে ট্যাপ করুন।")
        }
    }

    private suspend fun handleClick(input: String, lower: String) {
        val target = cleanActionText(input, listOf("ক্লিক করো", "ক্লিক কর", "ট্যাপ করো", "ট্যাপ কর", "চাপো", "press", "click", "tap"))
            .removeSurrounding("\"").removeSurrounding("'")
        if (target.isEmpty()) { reply("Boss, কোথায় ক্লিক?"); return }
        val svc = JarvisAccessibilityService.instance
        if (svc?.searchAndClick(target) == true) {
            reply("\"$target\" ক্লিক করেছি Boss!")
        } else {
            reply("Boss, \"$target\" পাচ্ছি না।")
        }
    }

    private suspend fun handleScreenRead(lower: String) {
        val screenText = JarvisAccessibilityService.currentScreenText
        if (screenText.isBlank()) { reply("Boss, স্ক্রিনে টেক্সট নেই।"); return }
        val wantsEn = lower.contains("ইংলিশে") || lower.contains("english")
        val lang = if (wantsEn) "en" else "bn"
        val translated = translateText(screenText, lang)
        val prefix = if (wantsEn) "Screen says:" else "স্ক্রিনে লেখা:"
        reply("$prefix ${translated ?: screenText}")
    }

    private suspend fun handleVolume(lower: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when {
            lower.contains("বাড়াও") || lower.contains("up") || lower.contains("increase") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + max / 5).coerceAtMost(max), AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম বাড়িয়েছি Boss।")
            }
            lower.contains("কমাও") || lower.contains("down") || lower.contains("decrease") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (cur - max / 5).coerceAtLeast(0), AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম কমিয়েছি Boss।")
            }
            lower.contains("মিউট") || lower.contains("mute") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                reply("মিউট করেছি Boss।")
            }
            lower.contains("ফুল") || lower.contains("full") || lower.contains("max") -> {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                reply("ভলিউম ফুল করেছি Boss!")
            }
            else -> reply("Boss, বাড়াও/কমাও/মিউট/ফুল বলুন।")
        }
    }

    private suspend fun handleNavigation(lower: String) {
        val svc = JarvisAccessibilityService.instance
        when {
            lower.contains("ব্যাক") || lower.contains("back") -> { svc?.pressBack(); reply("ব্যাক গেছি Boss।") }
            lower.contains("হোম") || lower.contains("home") -> { svc?.pressHome(); reply("হোমে এসেছি Boss।") }
            lower.contains("নোটিফিকেশন") || lower.contains("notification") -> { svc?.openNotifications(); reply("নোটিফিকেশন খুলেছি Boss।") }
            lower.contains("স্ক্রল আপ") || lower.contains("scroll up") || lower.contains("উপরে") -> { svc?.scrollUp(); reply("উপরে স্ক্রল করেছি।") }
            lower.contains("স্ক্রল") || lower.contains("scroll") || lower.contains("নিচে") -> { svc?.scrollDown(); reply("নিচে স্ক্রল করেছি।") }
        }
    }

    private suspend fun handleReminder(input: String, lower: String) {
        val durationMs = parseTimeDuration(lower)
        val text = extractReminderText(input)
        val ms = if (durationMs > 0) durationMs else 3600_000L
        if (text.isNotEmpty()) {
            val r = Reminder(text = text, triggerTimeMillis = System.currentTimeMillis() + ms, isAcknowledged = false, createdAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { db.reminderDao().insert(r) }
            reminderManager.scheduleReminder(r)
            reply("Done Boss! ${formatDuration(ms)} পরে মনে করাব: \"$text\"")
        } else {
            reply("Boss, কী মনে করাব আর কখন — দুটোই বলুন।")
        }
    }

    private suspend fun handleReminderAck() {
        withContext(Dispatchers.IO) {
            db.reminderDao().getActiveReminders()
                .filter { it.triggerTimeMillis <= System.currentTimeMillis() }
                .forEach { db.reminderDao().markAcknowledged(it.id); db.reminderDao().deleteAcknowledged() }
        }
        reply("রিমাইন্ডার মুছেছি Boss।")
    }

    private suspend fun handleContactSave(input: String, lower: String) {
        val fromCall = lower.contains("ফোন দিল") || lower.contains("ফোন দিয়েছ") ||
                lower.contains("কল এসেছ") || lower.contains("কল দিয়েছ") ||
                lower.contains("last call") || lower.contains("এইমাত্র")

        var number: String? = null
        if (fromCall && PhoneCallReceiver.pendingNumber != null) {
            number = PhoneCallReceiver.pendingNumber
        }
        if (number == null) {
            val numberRegex = Regex("[+]?[0-9\\s\\-]{7,15}")
            number = numberRegex.find(input)?.value?.replace(Regex("[\\s\\-]"), "")
        }
        if (number == null) {
            number = pendingContactNumber
        }
        if (number == null) {
            reply("Boss, কোন নম্বর সেভ করব? নম্বরটা বলুন অথবা কল আসার পর বলুন।")
            return
        }

        val name = extractContactName(input, lower)
        if (name.isNotEmpty()) {
            updateOverlay("$number সেভ করছি...")
            if (saveContactToPhone(name, number)) {
                pendingContactNumber = null
                reply("Boss, টাস্ক কমপ্লিট! $number কে \"$name\" নামে কন্টাক্টে সেভ করে দিয়েছি!")
            } else {
                reply("Boss, কন্টাক্ট সেভ করতে পারলাম না। Contacts permission চেক করুন।")
            }
        } else {
            pendingContactNumber = number
            reply("Boss, নম্বর পেয়েছি: $number। কী নামে সেভ করব বলুন!")
        }
    }

    private fun extractContactName(input: String, lower: String): String {
        val nameIdx = lower.indexOf(" নামে ")
        if (nameIdx > 0) {
            val before = input.substring(0, nameIdx).trim()
            val words = before.split(" ")
            val skip = setOf("এই", "ওই", "তার", "যে", "নাম্বারটা", "নম্বরটা", "নাম্বার",
                "নম্বর", "ফোন", "দিল", "দিয়েছিল", "কল", "এসেছিল", "সেটা", "এটা",
                "ওটা", "এখন", "এইমাত্র", "আমাকে", "তুমি", "জার্ভিস")
            val nameWords = words.filter { it.lowercase() !in skip && it.length > 1 }
            if (nameWords.isNotEmpty()) return nameWords.joinToString(" ")
        }
        val asIdx = lower.indexOf("as ")
        if (asIdx > 0) {
            val after = input.substring(asIdx + 3).trim()
                .replace(Regex("(?i)(নামে|name|save|সেভ)"), "").trim()
            if (after.isNotEmpty()) return after
        }
        return ""
    }

    private suspend fun saveContactToPhone(name: String, number: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Save contact failed", e)
                false
            }
        }
    }

    private suspend fun handleAiQuestion(input: String) {
        updateOverlay("উত্তর আনছি...")
        aiBrain.provider = settings.getAiProvider()
        aiBrain.apiKey = settings.getAiApiKey()
        val screen = JarvisAccessibilityService.currentScreenText
        reply(aiBrain.ask(input, screen, settings.getLanguage()))
    }

    // ===================== CALL HANDLING =====================

    @Suppress("DEPRECATION")
    private suspend fun handleAnswerCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            reply("Boss, ফোন ধরলাম!")
        } catch (e: Exception) {
            Log.e(TAG, "Answer call failed", e)
            reply("Boss, ফোন ধরতে পারলাম না। নিজে ধরুন।")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun handleRejectCall() {
        try {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.endCall()
            reply("Boss, ফোন কেটে দিয়েছি!")
        } catch (e: Exception) {
            Log.e(TAG, "Reject call failed", e)
            reply("Boss, ফোন কাটতে পারলাম না।")
        }
    }

    suspend fun announceIncomingCall(name: String, number: String) {
        val display = if (name != number && name.isNotBlank()) "$name ($number)" else number
        reply("Boss, $display ফোন করছে! ধরবো নাকি কাটবো?")
    }

    suspend fun announceNotification(app: String, sender: String, message: String) {
        val short = if (message.length > 80) message.take(80) + "..." else message
        reply("Boss, $app এ $sender মেসেজ দিয়েছে: $short")
    }

    private suspend fun handleReadLastNotification() {
        val sender = JarvisNotificationListener.lastSender
        val message = JarvisNotificationListener.lastMessage
        val app = JarvisNotificationListener.lastApp

        if (sender != null && message != null) {
            reply("Boss, শেষ মেসেজ — $app এ $sender লিখেছে: $message")
        } else {
            reply("Boss, কোনো নতুন মেসেজ নেই।")
        }
    }

    // ===================== UTILITIES =====================

    private fun extractAppName(input: String): String {
        val patterns = listOf("ওপেন করো ", "ওপেন কর ", "খোলো ", "খুলো ", "চালু কর ", "চালাও ", "open ")
        var c = input
        for (p in patterns) c = c.replace(p, "", ignoreCase = true)
        return c.trim().ifEmpty { input }
    }

    private fun cleanActionText(input: String, removePatterns: List<String>): String {
        var c = input
        for (p in removePatterns) c = c.replace(p, " ", ignoreCase = true)
        return c.replace(Regex("\\s+"), " ").trim()
    }

    private fun findPackageName(appName: String): String? {
        val lower = appName.lowercase().trim()
        appPackages[lower]?.let { return it }
        for ((key, pkg) in appPackages) {
            if (lower.contains(key) || key.contains(lower)) return pkg
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            context.packageManager.queryIntentActivities(intent, 0).firstOrNull {
                val label = it.loadLabel(context.packageManager).toString().lowercase()
                label.contains(lower) || lower.contains(label)
            }?.activityInfo?.packageName
        } catch (_: Exception) { null }
    }

    private fun tryOpenPackage(packageName: String): Boolean {
        JarvisAccessibilityService.instance?.let { svc ->
            if (svc.openApp(packageName)) return true
        }
        fallbackPackages[packageName]?.forEach { fallback ->
            JarvisAccessibilityService.instance?.openApp(fallback)?.let { if (it) return true }
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(fallback)
                if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); return true }
            } catch (_: Exception) {}
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); true } else false
        } catch (_: Exception) { false }
    }

    private fun parseTimeDuration(l: String): Long {
        var ms = 0L
        Regex("(\\d+)\\s*(মিনিট|minute|min)").find(l)?.let { ms += (it.groupValues[1].toLongOrNull() ?: 0) * 60_000 }
        Regex("(\\d+)\\s*(ঘণ্টা|ঘন্টা|hour)").find(l)?.let { ms += (it.groupValues[1].toLongOrNull() ?: 0) * 3_600_000 }
        Regex("(\\d+)\\s*(দিন|day)").find(l)?.let { ms += (it.groupValues[1].toLongOrNull() ?: 0) * 86_400_000 }
        if (l.contains("আধা ঘণ্টা")) ms = 1_800_000
        if (l.contains("আগামীকাল") || l.contains("tomorrow")) ms = 86_400_000
        return ms
    }

    private fun extractReminderText(input: String): String {
        var t = input
        for (p in listOf("মনে করাইও", "মনে করিও", "মনে রাখো", "remind me", "মনে করাও", "পরে বলো")) {
            t = t.replace(p, "", ignoreCase = true)
        }
        t = t.replace(Regex("(\\d+\\s*(সপ্তাহ|দিন|ঘণ্টা|মিনিট|week|day|hour|minute)\\s*পরে?\\s*)"), "")
        t = t.replace(Regex("(আধা ঘণ্টা পরে?|আগামীকাল)\\s*"), "")
        return t.replace(Regex("^[—\\-–:]+"), "").trim()
    }

    private fun formatDuration(ms: Long) = when {
        ms >= 86_400_000 -> "${ms / 86_400_000} দিন"
        ms >= 3_600_000 -> "${ms / 3_600_000} ঘণ্টা"
        ms >= 60_000 -> "${ms / 60_000} মিনিট"
        else -> "কিছুক্ষণ"
    }

    private suspend fun translateText(text: String, targetLang: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=${java.net.URLEncoder.encode(text, "UTF-8")}"
            val resp = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).get().build()).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: return@withContext null
                val json = org.json.JSONArray(body)
                val segs = json.getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until segs.length()) { segs.optJSONArray(i)?.optString(0, "")?.let { sb.append(it) } }
                sb.toString().trim().ifEmpty { null }
            } else null
        } catch (_: Exception) { null }
    }

    private suspend fun reply(text: String) {
        saveMessage("assistant", text)
        JarvisForegroundService.lastReplyText = text
        JarvisForegroundService.replyListeners.forEach { it(text) }
        updateOverlay(text.take(60) + if (text.length > 60) "..." else "")
        try { voiceEngine.speak(text) } catch (_: Exception) {}
    }

    private fun updateOverlay(text: String) {
        try {
            context.startService(Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_TEXT
                putExtra(OverlayService.EXTRA_TEXT, text)
            })
        } catch (_: Exception) {}
    }

    private suspend fun saveMessage(role: String, content: String) {
        withContext(Dispatchers.IO) {
            db.messageDao().insert(ConversationMessage(role = role, content = content, timestamp = System.currentTimeMillis()))
        }
    }

    companion object {
        private const val TAG = "CommandProcessor"
        var pendingContactNumber: String? = null
    }
}

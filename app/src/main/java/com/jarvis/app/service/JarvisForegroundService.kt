package com.jarvis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.app.JarvisApplication
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.app.brain.CommandProcessor
import com.jarvis.app.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class JarvisForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var commandTimeoutJob: Job? = null
    private var safetyTimeoutJob: Job? = null
    private var processingJob: Job? = null
    private var watchdogJob: Job? = null
    private var wasMusicMuted = false
    private var wasSystemMuted = false
    private lateinit var settings: SettingsManager
    private lateinit var commandProcessor: CommandProcessor

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsManager(this)
        commandProcessor = CommandProcessor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNotification()
                goToStandby()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_START_LISTENING -> {
                switchToCommandMode()
            }
            ACTION_STOP_LISTENING -> {
                goToStandby()
            }
            ACTION_PROCESS_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                if (currentState == JarvisState.STANDBY) {
                    showOverlay()
                }
                processCommand(text)
            }
            ACTION_INCOMING_CALL -> {
                val name = intent.getStringExtra("caller_name") ?: "Unknown"
                val number = intent.getStringExtra("caller_number") ?: ""
                announceIncomingCall(name, number)
            }
            ACTION_NOTIFICATION -> {
                val app = intent.getStringExtra("app_name") ?: ""
                val sender = intent.getStringExtra("sender") ?: ""
                val message = intent.getStringExtra("message") ?: ""
                announceNotification(app, sender, message)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
            action = ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllJobs()
        watchdogJob?.cancel()
        stopCurrentListening()
        scope.cancel()
        instance = null
    }

    private fun cancelAllJobs() {
        commandTimeoutJob?.cancel()
        safetyTimeoutJob?.cancel()
        processingJob?.cancel()
    }

    private fun startForegroundNotification() {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, JarvisApplication.CHANNEL_FOREGROUND)
            .setContentTitle("JARVIS Active")
            .setContentText("\"Hey Jarvis\" বলুন — সবসময় শুনছি")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // === STATE TRANSITIONS ===

    private fun goToStandby() {
        cancelAllJobs()
        stopCurrentListening()
        currentListenMode = ListenMode.WAKE_WORD
        currentState = JarvisState.STANDBY
        stateListeners.forEach { it(currentState) }
        hideOverlay()
        startRecognizer()
        startWatchdog()
    }

    fun switchToCommandMode() {
        commandTimeoutJob?.cancel()
        safetyTimeoutJob?.cancel()
        stopCurrentListening()

        currentListenMode = ListenMode.COMMAND
        currentState = JarvisState.LISTENING
        stateListeners.forEach { it(currentState) }
        updateOverlayState("LISTENING")

        startRecognizer()
        startWatchdog()
    }

    private fun processCommand(text: String) {
        processingJob?.cancel()
        commandTimeoutJob?.cancel()

        currentState = JarvisState.PROCESSING
        stateListeners.forEach { it(currentState) }
        updateOverlayState("PROCESSING")

        safetyTimeoutJob?.cancel()
        safetyTimeoutJob = scope.launch {
            delay(45_000)
            Log.w(TAG, "Safety timeout — cancelling stuck processing")
            processingJob?.cancel()
            switchToCommandMode()
        }

        processingJob = scope.launch {
            try {
                commandProcessor.process(text)
                delay(500)
            } catch (e: Exception) {
                Log.e(TAG, "Command processing error", e)
            } finally {
                safetyTimeoutJob?.cancel()
                if (currentState != JarvisState.STANDBY) {
                    switchToCommandMode()
                }
            }
        }
    }

    // === WATCHDOG ===

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (true) {
                delay(5_000)
                if (!isListening && currentState != JarvisState.PROCESSING && currentState != JarvisState.SPEAKING) {
                    Log.w(TAG, "Watchdog: recognizer died (state=$currentState, mode=$currentListenMode), restarting")
                    restartCurrentMode()
                }
            }
        }
    }

    // === SPEECH RECOGNIZER ===

    private fun startRecognizer() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }
        isListening = true

        scope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@JarvisForegroundService)

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Log.d(TAG, "Ready (mode=$currentListenMode)")
                    }

                    override fun onBeginningOfSpeech() {
                        if (currentListenMode == ListenMode.COMMAND) {
                            commandTimeoutJob?.cancel()
                            currentState = JarvisState.LISTENING
                            stateListeners.forEach { it(currentState) }
                        }
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        rmsListeners.forEach { it(rmsdB) }
                    }

                    override fun onEndOfSpeech() {
                        if (currentListenMode == ListenMode.COMMAND) {
                            currentState = JarvisState.PROCESSING
                            stateListeners.forEach { it(currentState) }
                            updateOverlayState("PROCESSING")
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches.isNullOrEmpty()) {
                            restartCurrentMode()
                            return
                        }

                        Log.d(TAG, "All results: $matches")

                        when (currentListenMode) {
                            ListenMode.WAKE_WORD -> {
                                var wakeMatch: String? = null
                                for (m in matches) {
                                    val lower = m.lowercase().trim()
                                    if (containsWakeWord(lower)) {
                                        wakeMatch = m.trim()
                                        break
                                    }
                                }
                                if (wakeMatch != null) {
                                    Log.d(TAG, "Wake word found in: '$wakeMatch'")
                                    val command = extractCommandAfterWakeWord(wakeMatch)
                                    onWakeWordDetected(command)
                                } else {
                                    restartCurrentMode()
                                }
                            }
                            ListenMode.COMMAND -> {
                                val text = matches.firstOrNull()?.trim() ?: ""
                                if (text.isEmpty()) {
                                    restartCurrentMode()
                                    return
                                }
                                lastHeardText = text
                                textListeners.forEach { it(text) }
                                stopCurrentListening()
                                processCommand(text)
                            }
                        }
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.e(TAG, "Recognition error=$error mode=$currentListenMode")
                        scope.launch {
                            delay(if (error == SpeechRecognizer.ERROR_CLIENT) 1000 else 300)
                            restartCurrentMode()
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partials = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!partials.isNullOrEmpty()) {
                            if (currentListenMode == ListenMode.COMMAND) {
                                textListeners.forEach { it(partials.first()) }
                            } else {
                                for (partial in partials) {
                                    val lower = partial.lowercase()
                                    if (containsWakeWord(lower)) {
                                        Log.d(TAG, "Wake word in partial: '$lower'")
                                        break
                                    }
                                }
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                })

                val locale = settings.getLanguage()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(locale, "en-US"))
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

                    if (currentListenMode == ListenMode.WAKE_WORD) {
                        putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 10000L)
                        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 10000L)
                        putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 10000L)
                    } else {
                        putExtra("android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 5000L)
                        putExtra("android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 8000L)
                        putExtra("android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
                    }
                }
                if (currentListenMode == ListenMode.WAKE_WORD) {
                    muteRecognizerBeep()
                    speechRecognizer?.startListening(intent)
                    scope.launch {
                        delay(500)
                        unmuteRecognizerBeep()
                    }
                } else {
                    speechRecognizer?.startListening(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recognizer", e)
                isListening = false
                unmuteRecognizerBeep()
                scope.launch {
                    delay(2000)
                    restartCurrentMode()
                }
            }
        }
    }

    private fun restartCurrentMode() {
        stopCurrentListening()
        scope.launch {
            delay(200)
            if (currentListenMode == ListenMode.WAKE_WORD) {
                currentState = JarvisState.STANDBY
                stateListeners.forEach { it(currentState) }
                startRecognizer()
            } else {
                if (currentState != JarvisState.PROCESSING) {
                    currentState = JarvisState.LISTENING
                    stateListeners.forEach { it(currentState) }
                }
                startRecognizer()
            }
        }
    }

    // === WAKE WORD ===

    private fun containsWakeWord(lower: String): Boolean {
        return lower.contains("jarvis") || lower.contains("জার্ভিস") ||
                lower.contains("জারভিস") || lower.contains("jarvish") ||
                lower.contains("jarves") || lower.contains("jarvas") ||
                lower.contains("জার্বিস") || lower.contains("জারবিস") ||
                lower.contains("জাভিস") || lower.contains("jarwis") ||
                lower.contains("jervis") || lower.contains("জার্ভিশ")
    }

    private fun extractCommandAfterWakeWord(text: String): String? {
        val patterns = listOf(
            "hey jarvis ", "hello jarvis ", "hi jarvis ", "jarvis ",
            "হেই জার্ভিস ", "হ্যালো জার্ভিস ", "জার্ভিস ",
            "hey jarvish ", "hello jarvish ", "jarvish ",
            "হে জার্ভিস ", "হেই জারভিস ", "জারভিস "
        )
        val lower = text.lowercase()
        for (p in patterns) {
            val idx = lower.indexOf(p)
            if (idx >= 0) {
                val after = text.substring(idx + p.length).trim()
                return if (after.isNotEmpty()) after else null
            }
        }
        return null
    }

    fun onWakeWordDetected(command: String? = null) {
        cancelAllJobs()
        stopCurrentListening()

        scope.launch(Dispatchers.Main) {
            bringToForeground()
            showOverlay()
            updateOverlayState("LISTENING")

            val expandIntent = Intent(this@JarvisForegroundService, OverlayService::class.java).apply {
                action = OverlayService.ACTION_EXPAND
            }
            try { startService(expandIntent) } catch (_: Exception) {}

            currentState = JarvisState.SPEAKING
            stateListeners.forEach { it(currentState) }

            if (command != null && command.isNotBlank()) {
                commandProcessor.acknowledge("জি Boss")
                processCommand(command)
            } else {
                commandProcessor.acknowledge("জি Boss, আমি আছি। বলুন কী করব?")
                switchToCommandMode()
            }
        }
    }

    // === OVERLAY & UI ===

    private fun bringToForeground() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("FROM_WAKE_WORD", true)
        }
        startActivity(intent)
    }

    private fun updateOverlayState(state: String) {
        if (OverlayService.instance == null) return
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SET_STATE
            putExtra(OverlayService.EXTRA_STATE, state)
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    private fun hideOverlay() {
        if (OverlayService.instance == null) return
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    private fun announceIncomingCall(name: String, number: String) {
        if (currentState == JarvisState.PROCESSING) return
        scope.launch {
            showOverlay()
            commandProcessor.announceIncomingCall(name, number)
            switchToCommandMode()
        }
    }

    private fun announceNotification(app: String, sender: String, message: String) {
        if (currentState == JarvisState.PROCESSING) return
        scope.launch {
            commandProcessor.announceNotification(app, sender, message)
        }
    }

    private fun muteRecognizerBeep() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            wasMusicMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            wasSystemMuted = am.isStreamMute(AudioManager.STREAM_SYSTEM)
            if (!wasMusicMuted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            if (!wasSystemMuted) am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (_: Exception) {}
    }

    private fun unmuteRecognizerBeep() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (!wasMusicMuted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            if (!wasSystemMuted) am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
    }

    fun stopSpeechRecognition() {
        goToStandby()
    }

    private fun stopCurrentListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recognizer", e)
        }
        speechRecognizer = null
    }

    enum class JarvisState {
        STANDBY, LISTENING, PROCESSING, SPEAKING
    }

    enum class ListenMode {
        WAKE_WORD, COMMAND
    }

    companion object {
        const val TAG = "JarvisForeground"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.app.START"
        const val ACTION_STOP = "com.jarvis.app.STOP"
        const val ACTION_START_LISTENING = "com.jarvis.app.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.jarvis.app.STOP_LISTENING"
        const val ACTION_PROCESS_TEXT = "com.jarvis.app.PROCESS_TEXT"
        const val ACTION_INCOMING_CALL = "com.jarvis.app.INCOMING_CALL"
        const val ACTION_NOTIFICATION = "com.jarvis.app.NOTIFICATION"
        const val EXTRA_TEXT = "extra_text"

        var instance: JarvisForegroundService? = null
            private set

        var currentState: JarvisState = JarvisState.STANDBY
            private set

        var currentListenMode: ListenMode = ListenMode.WAKE_WORD
            private set

        var lastHeardText: String = ""
            private set

        var lastReplyText: String = ""

        val stateListeners = mutableListOf<(JarvisState) -> Unit>()
        val textListeners = mutableListOf<(String) -> Unit>()
        val rmsListeners = mutableListOf<(Float) -> Unit>()
        val replyListeners = mutableListOf<(String) -> Unit>()
    }
}

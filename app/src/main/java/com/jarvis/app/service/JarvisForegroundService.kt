package com.jarvis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
                startWakeWordListening()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_START_LISTENING -> {
                switchToCommandMode()
            }
            ACTION_STOP_LISTENING -> {
                stopCurrentListening()
                startWakeWordListening()
            }
            ACTION_PROCESS_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                scope.launch {
                    currentListenMode = ListenMode.COMMAND
                    currentState = JarvisState.PROCESSING
                    stateListeners.forEach { it(currentState) }
                    commandProcessor.process(text)
                    delay(500)
                    startWakeWordListening()
                }
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
        stopCurrentListening()
        commandTimeoutJob?.cancel()
        scope.cancel()
        instance = null
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

    // --- Wake Word Mode (STANDBY) ---
    private fun startWakeWordListening() {
        if (currentState == JarvisState.SPEAKING || currentState == JarvisState.PROCESSING) return

        currentListenMode = ListenMode.WAKE_WORD
        currentState = JarvisState.STANDBY
        stateListeners.forEach { it(currentState) }
        updateOverlayState("STANDBY")

        startRecognizer()
    }

    // --- Command Mode (LISTENING) ---
    fun switchToCommandMode() {
        commandTimeoutJob?.cancel()
        currentListenMode = ListenMode.COMMAND
        currentState = JarvisState.LISTENING
        stateListeners.forEach { it(currentState) }
        updateOverlayState("LISTENING")

        stopCurrentListening()
        startRecognizer()

        commandTimeoutJob = scope.launch {
            delay(15_000)
            if (currentState == JarvisState.LISTENING) {
                startWakeWordListening()
            }
        }
    }

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
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""

                        if (text.isEmpty()) {
                            restartCurrentMode()
                            return
                        }

                        when (currentListenMode) {
                            ListenMode.WAKE_WORD -> {
                                val lower = text.lowercase()
                                if (containsWakeWord(lower)) {
                                    val command = extractCommandAfterWakeWord(text)
                                    onWakeWordDetected(command)
                                } else {
                                    restartCurrentMode()
                                }
                            }
                            ListenMode.COMMAND -> {
                                lastHeardText = text
                                textListeners.forEach { it(text) }
                                scope.launch {
                                    commandProcessor.process(text)
                                    delay(1000)
                                    startWakeWordListening()
                                }
                            }
                        }
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.e(TAG, "Recognition error=$error mode=$currentListenMode")
                        if (error != SpeechRecognizer.ERROR_CLIENT) {
                            scope.launch {
                                delay(800)
                                restartCurrentMode()
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrEmpty() && currentListenMode == ListenMode.COMMAND) {
                            textListeners.forEach { it(partial) }
                        }
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                })

                val locale = settings.getLanguage()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recognizer", e)
                isListening = false
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
            delay(300)
            if (currentListenMode == ListenMode.WAKE_WORD) {
                startWakeWordListening()
            } else {
                isListening = false
                startRecognizer()
            }
        }
    }

    private fun containsWakeWord(lower: String): Boolean {
        return lower.contains("jarvis") || lower.contains("জার্ভিস") ||
                lower.contains("জারভিস") || lower.contains("jarvish") ||
                lower.contains("jarves") || lower.contains("jarvas") ||
                lower.contains("জার্বিস") || lower.contains("জারবিস")
    }

    private fun extractCommandAfterWakeWord(text: String): String? {
        val patterns = listOf(
            "hey jarvis ", "hello jarvis ", "hi jarvis ", "jarvis ",
            "হেই জার্ভিস ", "হ্যালো জার্ভিস ", "জার্ভিস ",
            "hey jarvish ", "hello jarvish ", "jarvish "
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
        scope.launch(Dispatchers.Main) {
            bringToForeground()

            updateOverlayState("LISTENING")
            val expandIntent = Intent(this@JarvisForegroundService, OverlayService::class.java).apply {
                action = OverlayService.ACTION_EXPAND
            }
            try { startService(expandIntent) } catch (_: Exception) {}

            currentState = JarvisState.SPEAKING
            stateListeners.forEach { it(currentState) }

            if (command != null && command.isNotBlank()) {
                commandProcessor.acknowledge("জি Boss")
                currentState = JarvisState.PROCESSING
                stateListeners.forEach { it(currentState) }
                commandProcessor.process(command)
                delay(1000)
                startWakeWordListening()
            } else {
                commandProcessor.acknowledge("জি Boss, আমি আছি")
                switchToCommandMode()
            }
        }
    }

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
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SET_STATE
            putExtra(OverlayService.EXTRA_STATE, state)
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    fun stopSpeechRecognition() {
        stopCurrentListening()
        startWakeWordListening()
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

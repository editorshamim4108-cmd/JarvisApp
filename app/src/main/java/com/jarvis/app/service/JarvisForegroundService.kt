package com.jarvis.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JarvisForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var wakeWordJob: Job? = null
    private var audioRecord: AudioRecord? = null
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
                startWakeWordDetection()
            }
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_START_LISTENING -> {
                startSpeechRecognition()
            }
            ACTION_STOP_LISTENING -> {
                stopSpeechRecognition()
            }
            ACTION_PROCESS_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                scope.launch {
                    commandProcessor.process(text)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopWakeWordDetection()
        stopSpeechRecognition()
        scope.cancel()
        instance = null
    }

    private fun startForegroundNotification() {
        val tapIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, JarvisApplication.CHANNEL_FOREGROUND)
            .setContentTitle("JARVIS Active")
            .setContentText("\"Hey Jarvis\" বলুন বা ট্যাপ করুন")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // --- Wake Word Detection ---
    // Uses simple audio energy detection as a placeholder.
    // In production, integrate Porcupine SDK:
    //   val porcupine = Porcupine.Builder()
    //       .setAccessKey("YOUR_PICOVOICE_KEY")
    //       .setKeywordPaths(arrayOf("hey_jarvis.ppn"))
    //       .build(context)
    // For now, this monitors audio and triggers on any speech detected.
    private fun startWakeWordDetection() {
        wakeWordJob = scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid audio buffer size")
                    return@launch
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize / 2)

                // Placeholder wake word detection:
                // In production, feed buffer to Porcupine and check keywordIndex >= 0
                // For now, the user activates via the orb button or overlay bubble.
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        // Porcupine integration point:
                        // val keywordIndex = porcupine.process(buffer)
                        // if (keywordIndex >= 0) { onWakeWordDetected() }
                    }
                    delay(100)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Mic permission not granted", e)
            } catch (e: Exception) {
                Log.e(TAG, "Wake word detection error", e)
            }
        }
    }

    private fun stopWakeWordDetection() {
        wakeWordJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }
        audioRecord = null
    }

    fun onWakeWordDetected() {
        scope.launch(Dispatchers.Main) {
            currentState = JarvisState.LISTENING
            stateListeners.forEach { it(currentState) }
            startSpeechRecognition()
        }
    }

    // --- Speech Recognition ---
    private fun startSpeechRecognition() {
        if (isListening) return
        isListening = true
        currentState = JarvisState.LISTENING
        stateListeners.forEach { it(currentState) }

        scope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@JarvisForegroundService)

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Log.d(TAG, "Ready for speech")
                    }

                    override fun onBeginningOfSpeech() {
                        currentState = JarvisState.LISTENING
                        stateListeners.forEach { it(currentState) }
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        rmsListeners.forEach { it(rmsdB) }
                    }

                    override fun onEndOfSpeech() {
                        currentState = JarvisState.PROCESSING
                        stateListeners.forEach { it(currentState) }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            lastHeardText = text
                            textListeners.forEach { it(text) }
                            scope.launch {
                                commandProcessor.process(text)
                            }
                        } else {
                            currentState = JarvisState.STANDBY
                            stateListeners.forEach { it(currentState) }
                        }
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.e(TAG, "Speech recognition error: $error")
                        // Auto-restart listening after errors (except user-cancelled)
                        if (error != SpeechRecognizer.ERROR_CLIENT) {
                            scope.launch {
                                delay(1000)
                                if (currentState != JarvisState.STANDBY) {
                                    startSpeechRecognition()
                                }
                            }
                        } else {
                            currentState = JarvisState.STANDBY
                            stateListeners.forEach { it(currentState) }
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!partial.isNullOrEmpty()) {
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
                Log.e(TAG, "Failed to start speech recognition", e)
                isListening = false
            }
        }
    }

    fun stopSpeechRecognition() {
        isListening = false
        currentState = JarvisState.STANDBY
        stateListeners.forEach { it(currentState) }
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }
        speechRecognizer = null
    }

    enum class JarvisState {
        STANDBY, LISTENING, PROCESSING, SPEAKING
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

        var lastHeardText: String = ""
            private set

        var lastReplyText: String = ""

        val stateListeners = mutableListOf<(JarvisState) -> Unit>()
        val textListeners = mutableListOf<(String) -> Unit>()
        val rmsListeners = mutableListOf<(Float) -> Unit>()
        val replyListeners = mutableListOf<(String) -> Unit>()
    }
}

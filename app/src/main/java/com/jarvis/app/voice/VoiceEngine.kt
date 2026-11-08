package com.jarvis.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.jarvis.app.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class VoiceEngine(private val context: Context) {

    private val settings = SettingsManager(context)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val locale = when {
                    settings.getLanguage().startsWith("hi") -> Locale("hi", "IN")
                    settings.getLanguage().startsWith("bn") -> Locale("bn", "BD")
                    else -> Locale("bn", "BD")
                }
                tts?.language = locale
                tts?.setSpeechRate(settings.getSpeed())
            }
        }
    }

    suspend fun speak(text: String) {
        val cleanText = sanitizePronunciation(text)
        stopCurrent()

        val mode = settings.getPreferredMode()
        when (mode) {
            "FREE_FIRST" -> {
                speakWithFreeTts(cleanText)
                return
            }
            "KEY2_FIRST" -> {
                if (tryElevenLabs(settings.getKey2(), cleanText)) return
                if (tryElevenLabs(settings.getKey1(), cleanText)) return
                speakWithFreeTts(cleanText)
            }
            else -> { // AUTO
                if (tryElevenLabs(settings.getKey1(), cleanText)) return
                if (tryElevenLabs(settings.getKey2(), cleanText)) return
                speakWithFreeTts(cleanText)
            }
        }
    }

    private suspend fun tryElevenLabs(apiKey: String, text: String): Boolean {
        if (apiKey.isBlank()) return false

        return withContext(Dispatchers.IO) {
            val voiceId = settings.getVoiceId()
            val models = listOf("eleven_v3", "eleven_multilingual_v2", "eleven_flash_v2_5")

            for (modelId in models) {
                try {
                    val body = JSONObject().apply {
                        put("text", text)
                        put("model_id", modelId)
                        put("voice_settings", JSONObject().apply {
                            put("stability", 0.52)
                            put("similarity_boost", 0.82)
                        })
                    }

                    val request = Request.Builder()
                        .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("xi-api-key", apiKey)
                        .addHeader("Accept", "audio/mpeg")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val audioBytes = response.body?.bytes() ?: continue
                        playAudioBytes(audioBytes)
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ElevenLabs error with model $modelId", e)
                }
            }
            false
        }
    }

    private suspend fun playAudioBytes(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                val tempFile = File.createTempFile("jarvis_audio", ".mp3", context.cacheDir)
                FileOutputStream(tempFile).use { it.write(bytes) }

                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setPlaybackParams(playbackParams.setSpeed(settings.getSpeed()))
                        prepare()
                        setOnCompletionListener {
                            it.release()
                            tempFile.delete()
                        }
                        start()
                    }
                }

                // Wait for completion
                suspendCancellableCoroutine<Unit> { cont ->
                    mediaPlayer?.setOnCompletionListener {
                        it.release()
                        tempFile.delete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation {
                        mediaPlayer?.release()
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error", e)
            }
        }
    }

    private suspend fun speakWithFreeTts(text: String) {
        // First try Google Translate TTS (better quality for Bangla)
        val success = tryGoogleTranslateTts(text)
        if (!success) {
            // Fallback to Android built-in TTS
            speakWithAndroidTts(text)
        }
    }

    private suspend fun tryGoogleTranslateTts(text: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val lang = when {
                    settings.getLanguage().startsWith("hi") -> "hi"
                    settings.getLanguage().startsWith("en") -> "en"
                    else -> "bn"
                }
                val shortText = text.take(200) // Google TTS has length limits
                val url = "https://translate.googleapis.com/translate_tts?" +
                        "ie=UTF-8&client=tw-ob&tl=$lang&q=${java.net.URLEncoder.encode(shortText, "UTF-8")}"

                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes() ?: return@withContext false
                    playAudioBytes(audioBytes)
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "Google TTS error", e)
                false
            }
        }
    }

    private suspend fun speakWithAndroidTts(text: String) {
        if (!ttsReady || tts == null) {
            initTts()
            return
        }

        withContext(Dispatchers.Main) {
            tts?.setSpeechRate(settings.getSpeed())
            suspendCancellableCoroutine<Unit> { cont ->
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(utteranceId: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_${System.currentTimeMillis()}")
                cont.invokeOnCancellation {
                    tts?.stop()
                }
            }
        }
    }

    private fun sanitizePronunciation(text: String): String {
        return text
            .replace("বসকে", "Boss-কে")
            .replace("বসের", "Boss-এর")
            .replace(Regex("বস([,।!?\\s]|\$)"), "Boss$1")
    }

    fun stopCurrent() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun destroy() {
        stopCurrent()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "VoiceEngine"
    }
}

package com.jarvis.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "jarvis_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if encryption fails
        context.getSharedPreferences("jarvis_prefs_fallback", Context.MODE_PRIVATE)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    // --- API Keys (encrypted) ---
    fun getKey1(): String = securePrefs.getString(KEY_ELEVENLABS_1, "") ?: ""
    fun setKey1(key: String) = securePrefs.edit().putString(KEY_ELEVENLABS_1, key).apply()

    fun getKey2(): String = securePrefs.getString(KEY_ELEVENLABS_2, "") ?: ""
    fun setKey2(key: String) = securePrefs.edit().putString(KEY_ELEVENLABS_2, key).apply()

    // --- Voice Settings ---
    fun getVoiceId(): String = prefs.getString(KEY_VOICE_ID, DEFAULT_VOICE_ID) ?: DEFAULT_VOICE_ID
    fun setVoiceId(id: String) = prefs.edit().putString(KEY_VOICE_ID, id).apply()

    fun getSpeed(): Float = prefs.getFloat(KEY_SPEED, 1.0f)
    fun setSpeed(speed: Float) = prefs.edit().putFloat(KEY_SPEED, speed).apply()

    fun getPreferredMode(): String = prefs.getString(KEY_PREFERRED_MODE, "AUTO") ?: "AUTO"
    fun setPreferredMode(mode: String) = prefs.edit().putString(KEY_PREFERRED_MODE, mode).apply()

    // --- Language ---
    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "bn-BD") ?: "bn-BD"
    fun setLanguage(lang: String) = prefs.edit().putString(KEY_LANGUAGE, lang).apply()

    // --- Feature Toggles ---
    fun isWakeWordEnabled(): Boolean = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
    fun setWakeWordEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()

    fun isOverlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
    fun setOverlayEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()

    fun isAutoDeleteReminders(): Boolean = prefs.getBoolean(KEY_AUTO_DELETE_REMINDERS, true)
    fun setAutoDeleteReminders(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_DELETE_REMINDERS, enabled).apply()

    // --- First Launch ---
    fun isFirstLaunch(): Boolean = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    fun markFirstLaunchDone() = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()

    companion object {
        private const val KEY_ELEVENLABS_1 = "elevenlabs_key_1"
        private const val KEY_ELEVENLABS_2 = "elevenlabs_key_2"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_SPEED = "voice_speed"
        private const val KEY_PREFERRED_MODE = "preferred_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_AUTO_DELETE_REMINDERS = "auto_delete_reminders"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        const val DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb" // George
    }

    // Voice character options for UI
    data class VoiceOption(val id: String, val name: String)

    val voiceOptions = listOf(
        VoiceOption("JBFqnCBsd6RMkjVDRZzb", "George (Warm Jarvis)"),
        VoiceOption("pNInz6obpgDQGcFmaJgB", "Adam (Deep Cinematic)"),
        VoiceOption("onwK4e9ZLuTAKqWW03F9", "Daniel (Crisp)"),
        VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel (Female)"),
    )
}
